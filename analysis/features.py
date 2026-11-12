"""Turns raw transactions into model features, using only what was known when each transaction arrived.

Every feature is computed by walking one card's transactions in time order and looking at the state built from the
transactions *before* the current one; the state is updated only after the row's features are written. So no feature
can see the future, and none can see another card. tests/test_features.py checks this by changing later rows and
confirming earlier rows' features don't move.

What is deliberately left out:
  - issue_type, edge_case, scenario_id, persona: the answer key, or close to it.
  - rule_alerts: the thing being compared against.
  - the merchant's name and the country code themselves. The simulator gives fraud its own fictional merchants and
    a fixed list of probe merchants, so a model could memorize names and score well without learning any behavior.
    The model only gets what the name and country *mean for this card*: seen before or not, how often, how long ago.
  - user and card IDs.
"""

from collections import defaultdict, deque

import numpy as np
import pandas as pd

SMALL_CHARGE_CENTS = 200  # "tiny charge", the same line the card-testing rule draws
NAN = float("nan")

NUMERIC_FEATURES = [
    "amount",                    # dollars
    "hour",                      # hour of day, UTC
    "card_prior_tx",             # how much history this card has
    "amount_vs_card_mean",       # this amount / the card's average amount so far
    "country_is_new",            # 1 if the card has never been used in this country
    "days_since_travel",         # days since the card's last TRAVEL purchase
    "small_charges_60m",         # charges of $2 or less in the previous 60 minutes
    "tx_60m",                    # all charges in the previous 60 minutes
    "tx_24h",                    # all charges in the previous 24 hours
    "secs_since_identical",      # seconds since the same merchant charged the same amount
    "merchant_prior_tx",         # earlier charges at this merchant
    "days_since_merchant",       # days since the last charge at this merchant
    "last_amount_at_merchant",   # dollars
    "amount_vs_last_at_merchant",  # (this + $1) / (last + $1), so a $0 trial doesn't divide by zero
    "prev_gap_days_at_merchant",   # days between the two charges before this one at this merchant
    "prev_two_amounts_equal",    # 1 if those two were within 1% of each other
    "cat_day_total",             # dollars spent in this category today, including this charge
    "cat_prior_days",            # earlier days with spending in this category
    "cat_day_vs_usual",          # today's total / the usual day's total in this category
    "cat_day_z",                 # the same comparison in standard deviations
]
CATEGORICAL_FEATURES = ["category"]
FEATURES = NUMERIC_FEATURES + CATEGORICAL_FEATURES


class _DayStats:
    """One category's finished days on one card: count, mean, and spread, kept as running numbers."""

    __slots__ = ("day", "today", "n", "mean", "m2")

    def __init__(self):
        self.day, self.today, self.n, self.mean, self.m2 = None, 0.0, 0, 0.0, 0.0

    def close_day(self):
        self.n += 1
        delta = self.today - self.mean
        self.mean += delta / self.n
        self.m2 += delta * (self.today - self.mean)

    def std(self):
        return (self.m2 / self.n) ** 0.5 if self.n > 1 else 0.0


def _card_features(times, amounts, merchants, categories, countries):
    """Features for one card's transactions, already in time order. Returns one list per transaction."""
    rows = []
    n_prior, amount_sum = 0, 0.0
    seen_countries, last_travel = set(), None
    recent_small, recent_hour, recent_day = deque(), deque(), deque()
    identical = {}                      # (merchant, amount) -> time of the last such charge
    by_merchant = defaultdict(list)     # merchant -> [(time, amount), ...], the last two kept
    merchant_count = defaultdict(int)
    by_category = defaultdict(_DayStats)

    for t, cents, merchant, category, country in zip(times, amounts, merchants, categories, countries):
        dollars = cents / 100.0
        # --- read the state: everything below describes the past ---
        while recent_small and recent_small[0] < t - 3600:
            recent_small.popleft()
        while recent_hour and recent_hour[0] < t - 3600:
            recent_hour.popleft()
        while recent_day and recent_day[0] < t - 86400:
            recent_day.popleft()
        history = by_merchant[merchant]
        last = history[-1] if history else None
        before_last = history[-2] if len(history) > 1 else None
        same = identical.get((merchant, cents))
        stats = by_category[category]
        day = int(t // 86400)
        if stats.day is not None and day > stats.day:
            stats.close_day()           # yesterday (or earlier) is finished: it joins the baseline
            stats.today = 0.0
        if stats.day is None or day > stats.day:
            stats.day = day
        today_total = stats.today + dollars
        std = stats.std()

        rows.append([
            dollars,
            (t % 86400) // 3600,
            n_prior,
            dollars / (amount_sum / n_prior) if n_prior and amount_sum > 0 else NAN,
            0.0 if country in seen_countries else 1.0,
            (t - last_travel) / 86400 if last_travel is not None else NAN,
            len(recent_small),
            len(recent_hour),
            len(recent_day),
            t - same if same is not None else NAN,
            merchant_count[merchant],
            (t - last[0]) / 86400 if last else NAN,
            last[1] if last else NAN,
            (dollars + 1) / (last[1] + 1) if last else NAN,
            (last[0] - before_last[0]) / 86400 if before_last else NAN,
            (1.0 if abs(last[1] - before_last[1]) <= 0.01 * before_last[1] else 0.0) if before_last else NAN,
            today_total,
            stats.n,
            today_total / stats.mean if stats.n and stats.mean > 0 else NAN,
            (today_total - stats.mean) / std if std > 0 else NAN,
        ])

        # --- only now learn from this transaction ---
        n_prior += 1
        amount_sum += dollars
        seen_countries.add(country)
        if category == "TRAVEL":
            last_travel = t
        if cents <= SMALL_CHARGE_CENTS:
            recent_small.append(t)
        recent_hour.append(t)
        recent_day.append(t)
        identical[(merchant, cents)] = t
        by_merchant[merchant] = (history + [(t, dollars)])[-2:]
        merchant_count[merchant] += 1
        stats.today = today_total
    return rows


def build_features(transactions: pd.DataFrame) -> pd.DataFrame:
    """One row of FEATURES per transaction, with the same index as the input.

    Needs card_id, occurred_at, amount_cents, merchant, category, country. INCOME rows must already be removed
    (the rules never look at income either).
    """
    seconds = pd.to_datetime(transactions["occurred_at"], utc=True, format="ISO8601").astype("int64") / 1e9
    ordered = transactions.assign(_t=seconds).sort_values(["card_id", "_t"], kind="stable")
    out_index, out_rows = [], []
    for _, card in ordered.groupby("card_id", sort=False):
        out_index.extend(card.index)
        out_rows.extend(_card_features(card["_t"].to_numpy(), card["amount_cents"].to_numpy(),
                                       card["merchant"].to_numpy(), card["category"].to_numpy(),
                                       card["country"].to_numpy()))
    features = pd.DataFrame(np.array(out_rows, dtype=float), index=out_index, columns=NUMERIC_FEATURES)
    features["category"] = transactions["category"]
    return features.loc[transactions.index, FEATURES]
