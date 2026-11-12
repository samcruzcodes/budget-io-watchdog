import numpy as np
import pandas as pd

from features import FEATURES, build_features


def tx(card, when, merchant, cents, category="SHOPPING", country="US"):
    return {"card_id": card, "occurred_at": when, "merchant": merchant, "amount_cents": cents,
            "category": category, "country": country}


def frame(*rows):
    return pd.DataFrame(list(rows))


def test_the_answer_key_and_identities_are_not_features():
    forbidden = {"issue_type", "edge_case", "scenario_id", "persona", "rule_alerts", "is_issue", "merchant",
                 "country", "user_id", "card_id", "transaction_id", "source"}
    assert not forbidden & set(FEATURES)


def test_a_rows_features_do_not_change_when_later_transactions_change():
    history = [tx("card-1", f"2026-03-{day:02d}T12:00:00Z", "Chipotle", 1200 + day, "DINING") for day in range(1, 11)]
    quiet_future = history + [tx("card-1", "2026-03-20T12:00:00Z", "Chipotle", 1300, "DINING")]
    wild_future = history + [
        tx("card-1", "2026-03-11T12:00:00Z", "Chipotle", 990_000, "DINING"),
        tx("card-1", "2026-03-11T12:00:05Z", "Luxury Goods Outlet", 500_000, "SHOPPING", "FR"),
        tx("card-1", "2026-03-11T12:00:10Z", "Delta Air Lines", 80_000, "TRAVEL"),
    ]

    a = build_features(frame(*quiet_future)).iloc[:10]
    b = build_features(frame(*wild_future)).iloc[:10]

    pd.testing.assert_frame_equal(a, b)


def test_one_cards_history_never_reaches_another_card():
    alone = frame(tx("card-1", "2026-03-01T12:00:00Z", "Whole Foods Market", 6427, "GROCERIES"))
    together = frame(
        tx("card-2", "2026-02-27T12:00:00Z", "Whole Foods Market", 6427, "GROCERIES"),
        tx("card-2", "2026-03-01T11:59:40Z", "Whole Foods Market", 6427, "GROCERIES"),
        tx("card-1", "2026-03-01T12:00:00Z", "Whole Foods Market", 6427, "GROCERIES"),
    )

    pd.testing.assert_frame_equal(build_features(alone).reset_index(drop=True),
                                  build_features(together).iloc[[2]].reset_index(drop=True))


def test_rows_come_back_in_the_order_they_were_given_even_if_that_is_not_time_order():
    shuffled = frame(
        tx("card-1", "2026-03-02T12:00:00Z", "Shell", 4000, "TRANSPORTATION"),
        tx("card-1", "2026-03-01T12:00:00Z", "Shell", 3000, "TRANSPORTATION"),
    )

    features = build_features(shuffled)

    assert list(features["amount"]) == [40.0, 30.0]
    assert list(features["card_prior_tx"]) == [1, 0]  # the March 2 charge has one earlier charge; March 1 has none


def test_an_identical_charge_seconds_later_is_visible():
    features = build_features(frame(
        tx("card-1", "2026-03-01T12:00:00Z", "Whole Foods Market", 6427, "GROCERIES"),
        tx("card-1", "2026-03-01T12:00:24Z", "Whole Foods Market", 6427, "GROCERIES"),
        tx("card-1", "2026-03-01T12:01:00Z", "Whole Foods Market", 6500, "GROCERIES"),  # a different amount
    ))

    assert np.isnan(features["secs_since_identical"].iloc[0])
    assert features["secs_since_identical"].iloc[1] == 24
    assert np.isnan(features["secs_since_identical"].iloc[2])


def test_small_charges_in_the_last_hour_are_counted_before_the_big_one():
    features = build_features(frame(
        tx("card-1", "2026-03-01T09:00:00Z", "SnackHub Vending", 100),   # more than an hour before the last charge
        tx("card-1", "2026-03-01T12:00:00Z", "PixelGames Online", 50),
        tx("card-1", "2026-03-01T12:05:00Z", "QuickTune Downloads", 199),
        tx("card-1", "2026-03-01T12:10:00Z", "ElectroMart Online", 48_900),
    ))

    assert list(features["small_charges_60m"]) == [0, 0, 1, 2]
    assert features["tx_24h"].iloc[3] == 3


def test_a_new_country_and_the_last_travel_purchase():
    features = build_features(frame(
        tx("card-1", "2026-03-01T12:00:00Z", "Safeway", 5000, "GROCERIES"),
        tx("card-1", "2026-03-03T12:00:00Z", "Delta Air Lines", 40_000, "TRAVEL"),
        tx("card-1", "2026-03-08T12:00:00Z", "Cafe de Flore", 1850, "DINING", "FR"),
        tx("card-1", "2026-03-09T12:00:00Z", "Cafe de Flore", 2100, "DINING", "FR"),
    ))

    assert list(features["country_is_new"]) == [1, 0, 1, 0]  # a card's very first country is new too
    assert np.isnan(features["days_since_travel"].iloc[0])
    assert features["days_since_travel"].iloc[2] == 5


def test_a_price_change_at_a_merchant_is_compared_with_the_last_two_charges():
    features = build_features(frame(
        tx("card-1", "2026-01-01T08:00:00Z", "Netflix", 1549, "SUBSCRIPTIONS"),
        tx("card-1", "2026-01-31T08:00:00Z", "Netflix", 1549, "SUBSCRIPTIONS"),
        tx("card-1", "2026-03-02T08:00:00Z", "Netflix", 1849, "SUBSCRIPTIONS"),
    ))

    hike = features.iloc[2]
    assert hike["last_amount_at_merchant"] == 15.49
    assert hike["amount_vs_last_at_merchant"] == (18.49 + 1) / (15.49 + 1)
    assert hike["prev_gap_days_at_merchant"] == 30
    assert hike["prev_two_amounts_equal"] == 1
    assert hike["merchant_prior_tx"] == 2


def test_a_days_category_total_is_compared_with_earlier_days_only():
    features = build_features(frame(
        tx("card-1", "2026-03-01T12:00:00Z", "Chipotle", 2000, "DINING"),
        tx("card-1", "2026-03-02T12:00:00Z", "Chipotle", 4000, "DINING"),
        tx("card-1", "2026-03-03T12:00:00Z", "Chipotle", 3000, "DINING"),
        tx("card-1", "2026-03-03T19:00:00Z", "Sushi Zen", 15_000, "DINING"),
        tx("card-1", "2026-03-03T20:00:00Z", "Target", 9000, "SHOPPING"),  # another category starts from nothing
    ))

    assert list(features["cat_day_total"]) == [20.0, 40.0, 30.0, 180.0, 90.0]
    assert list(features["cat_prior_days"]) == [0, 1, 2, 2, 0]
    assert features["cat_day_vs_usual"].iloc[3] == 180.0 / 30.0  # the two finished days averaged $30
    assert np.isnan(features["cat_day_vs_usual"].iloc[0])
