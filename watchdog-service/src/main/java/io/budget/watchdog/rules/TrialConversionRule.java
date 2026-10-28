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
 * Flags a free or $1 trial that turned into a paid plan: a tiny charge, then the same merchant charging much more a
 * few days or weeks later. A $0.99 App Store purchase followed by a $9.99 one looks the same, so it's an expected
 * false positive.
 */
@Component
class TrialConversionRule implements DetectionRule {

    @ConfigurationProperties("watchdog.rules.trial-conversion")
    record Settings(long maxTrialCents, int minMultiple, Duration minDelay, Duration maxDelay) {

        Settings {
            Check.positive("watchdog.rules.trial-conversion.max-trial-cents", maxTrialCents);
            Check.positive("watchdog.rules.trial-conversion.min-multiple", minMultiple);
            Check.positive("watchdog.rules.trial-conversion.min-delay", minDelay);
            Check.positive("watchdog.rules.trial-conversion.max-delay", maxDelay);
            Check.require(minDelay.compareTo(maxDelay) < 0,
                    "watchdog.rules.trial-conversion.min-delay must be less than max-delay");
        }
    }

    private record SmallCharge(Instant occurredAt, long amountCents) {}

    private final Settings settings;

    // For each card, the latest small charge at each merchant from the last `maxDelay`. The inner map is only touched
    // under the engine's per-card lock, so a plain HashMap is enough.
    private final Map<String, Map<String, SmallCharge>> smallChargesByCard = new ConcurrentHashMap<>();

    TrialConversionRule(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "trial-conversion";
    }

    @Override
    public IssueType detects() {
        return IssueType.TRIAL_CONVERSION;
    }

    @Override
    public Optional<RuleHit> evaluate(Transaction tx) {
        Map<String, SmallCharge> small = smallChargesByCard.computeIfAbsent(tx.cardId(), card -> new HashMap<>());
        Instant cutoff = tx.occurredAt().minus(settings.maxDelay());
        small.values().removeIf(charge -> charge.occurredAt().isBefore(cutoff)); // too old to convert now

        SmallCharge earlier = small.get(tx.merchant());
        // "More than the trial amount" matters after a $0 signup, where any multiple of $0 is still $0.
        if (earlier != null && tx.amountCents() > settings.maxTrialCents()) {
            if (tx.occurredAt().isBefore(earlier.occurredAt().plus(settings.minDelay()))) {
                // A real charge this soon means the small one was a hold (like at a gas pump) or a first purchase,
                // not a trial. Forget it, so a later charge from the same merchant can't look like a conversion.
                small.remove(tx.merchant());
            } else if (tx.amountCents() >= settings.minMultiple() * earlier.amountCents()) {
                small.remove(tx.merchant()); // one hit per trial; next month's charge is just a renewal
                return Optional.of(hit(("%s charged %s, %s after a %s charge. "
                        + "A free trial may have turned into a paid plan.")
                        .formatted(tx.merchant(), Money.format(tx.amountCents()),
                                Durations.days(Duration.between(earlier.occurredAt(), tx.occurredAt())),
                                Money.format(earlier.amountCents()))));
            }
        }
        if (tx.amountCents() <= settings.maxTrialCents()) {
            small.put(tx.merchant(), new SmallCharge(tx.occurredAt(), tx.amountCents()));
        }
        return Optional.empty();
    }

    /** How many small charges the rule is holding for a card (for the bounded-memory test). */
    int smallChargesHeldFor(String cardId) {
        Map<String, SmallCharge> small = smallChargesByCard.get(cardId);
        return small == null ? 0 : small.size();
    }
}
