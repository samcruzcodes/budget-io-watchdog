package io.budget.watchdog.rules;

import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Flags a subscription that got more expensive: a merchant that has charged about monthly at a steady price charges
 * more. Utility bills that vary every month occasionally repeat within the tolerance by chance, so they're an
 * expected false positive now and then.
 */
@Component
class PriceHikeRule implements DetectionRule {

    @ConfigurationProperties("watchdog.rules.price-hike")
    record Settings(Duration minGap, Duration maxGap, int tolerancePercent) {

        Settings {
            Check.positive("watchdog.rules.price-hike.min-gap", minGap);
            Check.positive("watchdog.rules.price-hike.max-gap", maxGap);
            Check.require(minGap.compareTo(maxGap) < 0, "watchdog.rules.price-hike.min-gap must be less than max-gap");
            Check.positive("watchdog.rules.price-hike.tolerance-percent", tolerancePercent);
        }

        MonthlyPattern monthlyPattern() {
            return new MonthlyPattern(minGap, maxGap, tolerancePercent);
        }
    }

    private record Charge(Instant occurredAt, long amountCents) {}

    /** A merchant's last two charges on a card. {@code older} is null until there have been two. */
    private record LastTwo(Charge older, Charge newer) {}

    private final Settings settings;
    private final MonthlyPattern monthly;

    // For each card, the last two charges at each merchant. The inner map is only touched under the engine's
    // per-card lock.
    private final Map<String, Map<String, LastTwo>> historyByCard = new ConcurrentHashMap<>();

    PriceHikeRule(Settings settings) {
        this.settings = settings;
        this.monthly = settings.monthlyPattern();
    }

    @Override
    public String id() {
        return "price-hike";
    }

    @Override
    public IssueType detects() {
        return IssueType.PRICE_HIKE;
    }

    @Override
    public Optional<RuleHit> evaluate(Transaction tx) {
        Map<String, LastTwo> merchants = historyByCard.computeIfAbsent(tx.cardId(), card -> new HashMap<>());
        // A merchant quiet for longer than maxGap can't be monthly anymore (its next charge fails the gap check
        // anyway), so forgetting it keeps memory bounded without changing any result.
        Instant cutoff = tx.occurredAt().minus(settings.maxGap());
        merchants.values().removeIf(last -> last.newer().occurredAt().isBefore(cutoff));

        Charge current = new Charge(tx.occurredAt(), tx.amountCents());
        LastTwo last = merchants.get(tx.merchant());
        Optional<RuleHit> result = Optional.empty();
        if (last != null && last.older() != null && isHike(last.older(), last.newer(), current)) {
            result = Optional.of(hit("%s charged %s, up from %s last month.".formatted(
                    tx.merchant(), Money.format(current.amountCents()), Money.format(last.newer().amountCents()))));
        }
        merchants.put(tx.merchant(), new LastTwo(last == null ? null : last.newer(), current));
        return result;
    }

    /** a and b look like a subscription (monthly, same price), and c is the next monthly charge at a higher price. */
    private boolean isHike(Charge a, Charge b, Charge c) {
        return monthly.isMonthly(a.occurredAt(), b.occurredAt())
                && monthly.isMonthly(b.occurredAt(), c.occurredAt())
                && monthly.isWithinTolerance(a.amountCents(), b.amountCents())
                && monthly.isHigher(b.amountCents(), c.amountCents());
    }

    /** How many merchants the rule is tracking for a card (for the bounded-memory test). */
    int merchantsHeldFor(String cardId) {
        Map<String, LastTwo> merchants = historyByCard.get(cardId);
        return merchants == null ? 0 : merchants.size();
    }
}
