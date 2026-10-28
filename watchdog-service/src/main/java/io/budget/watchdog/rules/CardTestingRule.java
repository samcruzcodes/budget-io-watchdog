package io.budget.watchdog.rules;

import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Flags a big charge that follows several tiny ones within an hour: someone checking that a stolen card number works
 * before using it. Several tolls and then a hotel on a road trip look the same, so they're an expected false positive.
 */
@Component
class CardTestingRule implements DetectionRule {

    @ConfigurationProperties("watchdog.rules.card-testing")
    record Settings(Duration window, long maxProbeCents, int minProbes, long minChargeCents) {

        Settings {
            Check.positive("watchdog.rules.card-testing.window", window);
            Check.positive("watchdog.rules.card-testing.max-probe-cents", maxProbeCents);
            Check.positive("watchdog.rules.card-testing.min-probes", minProbes);
            // Otherwise one charge could be both a probe and the big charge.
            Check.require(minChargeCents > maxProbeCents,
                    "watchdog.rules.card-testing.min-charge-cents must be more than max-probe-cents");
        }
    }

    private final Settings settings;

    // Times of each card's small charges from the last `window`, oldest first, changed under the engine's per-card
    // lock.
    private final Map<String, Deque<Instant>> probesByCard = new ConcurrentHashMap<>();

    CardTestingRule(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "card-testing";
    }

    @Override
    public IssueType detects() {
        return IssueType.CARD_TESTING;
    }

    @Override
    public Optional<RuleHit> evaluate(Transaction tx) {
        Deque<Instant> probes = probesByCard.computeIfAbsent(tx.cardId(), card -> new ArrayDeque<>());
        Instant cutoff = tx.occurredAt().minus(settings.window());
        while (!probes.isEmpty() && probes.peekFirst().isBefore(cutoff)) {
            probes.removeFirst();
        }

        if (tx.amountCents() >= settings.minChargeCents() && probes.size() >= settings.minProbes()) {
            String explanation = ("%d charges of %s or less in the %s before a %s charge at %s. "
                    + "Someone may be testing your card number.")
                    .formatted(probes.size(), Money.format(settings.maxProbeCents()),
                            Durations.describe(Duration.between(probes.peekFirst(), tx.occurredAt())),
                            Money.format(tx.amountCents()), tx.merchant());
            probes.clear(); // one hit per burst
            return Optional.of(hit(explanation));
        }
        if (tx.amountCents() <= settings.maxProbeCents()) {
            probes.addLast(tx.occurredAt());
        }
        return Optional.empty();
    }

    /** How many small charges the rule is holding for a card (for the bounded-memory test). */
    int probesHeldFor(String cardId) {
        Deque<Instant> probes = probesByCard.get(cardId);
        return probes == null ? 0 : probes.size();
    }
}
