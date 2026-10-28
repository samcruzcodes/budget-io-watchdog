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
 * Flags a charge that repeats an earlier one on the same card: same merchant, same amount, a few minutes apart. That's
 * what a payment glitch looks like. Buying the same coffee for a friend looks identical, so it's an expected false
 * positive; the same coffee hours later is outside the window.
 */
@Component
class DuplicateChargeRule implements DetectionRule {

    @ConfigurationProperties("watchdog.rules.duplicate-charge")
    record Settings(Duration window) {

        Settings {
            Check.positive("watchdog.rules.duplicate-charge.window", window);
        }
    }

    private record Charge(Instant occurredAt, String merchant, long amountCents) {}

    private final Settings settings;

    // Each card's charges from the last `window`, oldest first. The map is shared by the consumer threads; the deque
    // inside is only touched under the engine's per-card lock, so it doesn't need its own.
    private final Map<String, Deque<Charge>> recentByCard = new ConcurrentHashMap<>();

    DuplicateChargeRule(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "duplicate-charge";
    }

    @Override
    public IssueType detects() {
        return IssueType.DUPLICATE_CHARGE;
    }

    @Override
    public Optional<RuleHit> evaluate(Transaction tx) {
        Deque<Charge> recent = recentByCard.computeIfAbsent(tx.cardId(), card -> new ArrayDeque<>());
        Instant cutoff = tx.occurredAt().minus(settings.window());
        while (!recent.isEmpty() && recent.peekFirst().occurredAt().isBefore(cutoff)) {
            recent.removeFirst();
        }

        Charge latestMatch = null;
        int matches = 0;
        for (Charge charge : recent) {
            if (charge.merchant().equals(tx.merchant()) && charge.amountCents() == tx.amountCents()) {
                latestMatch = charge;
                matches++;
            }
        }
        recent.addLast(new Charge(tx.occurredAt(), tx.merchant(), tx.amountCents()));

        if (latestMatch == null) {
            return Optional.empty();
        }
        String amount = Money.format(tx.amountCents());
        String gap = Durations.describe(Duration.between(latestMatch.occurredAt(), tx.occurredAt()));
        // "Twice" is only true for a pair; a third identical charge gets its own wording.
        return Optional.of(hit(matches == 1
                ? "%s charged %s twice, %s apart.".formatted(tx.merchant(), amount, gap)
                : "%s charged %s again, %s after an identical charge.".formatted(tx.merchant(), amount, gap)));
    }

    /** How many charges the rule is holding for a card (for the bounded-memory test). */
    int chargesHeldFor(String cardId) {
        Deque<Charge> recent = recentByCard.get(cardId);
        return recent == null ? 0 : recent.size();
    }
}
