package io.budget.watchdog.rules;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Flags a charge from a country the card isn't normally used in, unless a travel purchase in the last few weeks
 * suggests a trip. A one-off order from a foreign online shop looks the same, so it's an expected false positive.
 */
@Component
class NewCountryRule implements DetectionRule {

    @ConfigurationProperties("watchdog.rules.new-country")
    record Settings(int minPriorTransactions, Duration travelLookback) {

        Settings {
            Check.positive("watchdog.rules.new-country.min-prior-transactions", minPriorTransactions);
            Check.positive("watchdog.rules.new-country.travel-lookback", travelLookback);
        }
    }

    /** What the rule knows about one card, changed under the engine's per-card lock. */
    private static final class CardHistory {
        final Set<String> countries = new HashSet<>();
        long transactions;
        Instant lastTravel; // null until the card's first TRAVEL charge
    }

    private final Settings settings;
    private final Map<String, CardHistory> historyByCard = new ConcurrentHashMap<>();

    NewCountryRule(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "new-country";
    }

    @Override
    public IssueType detects() {
        return IssueType.NEW_COUNTRY;
    }

    @Override
    public Optional<RuleHit> evaluate(Transaction tx) {
        CardHistory card = historyByCard.computeIfAbsent(tx.cardId(), id -> new CardHistory());

        Optional<RuleHit> result = Optional.empty();
        if (!card.countries.contains(tx.country())) {
            boolean stillLearning = card.transactions < settings.minPriorTransactions();
            boolean recentTravel = card.lastTravel != null
                    && !card.lastTravel.isBefore(tx.occurredAt().minus(settings.travelLookback()));
            if (stillLearning || recentTravel) {
                card.countries.add(tx.country());
            } else {
                // Not learned: if this is fraud, the rule shouldn't start treating the country as normal.
                result = Optional.of(hit(explain(tx)));
            }
        }

        // Learn only after checking, so a hotel abroad can't count as the travel that excuses itself.
        card.transactions++;
        if (tx.category() == Category.TRAVEL) {
            card.lastTravel = tx.occurredAt();
        }
        return result;
    }

    // Worded to stay true when a flagged country charges again, since flagged countries aren't learned.
    private String explain(Transaction tx) {
        String country = Locale.of("", tx.country()).getDisplayCountry(Locale.ENGLISH); // "FR" -> "France"
        return "%s at %s (%s), a country this card isn't normally used in, with no travel purchases in the last %s."
                .formatted(Money.format(tx.amountCents()), tx.merchant(), country,
                        Durations.days(settings.travelLookback()));
    }
}
