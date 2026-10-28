package io.budget.watchdog.rules;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Flags a day of unusually high spending in a category: today's total so far is several standard deviations above
 * this card's usual day in that category. Only impulse categories are watched (dining and shopping by default);
 * fixed bills like rent can't really spike, and their spread of zero breaks the math. A planned big purchase or a
 * trip looks the same, so those are expected false positives.
 */
@Component
class SpendingSpikeRule implements DetectionRule {

    @ConfigurationProperties("watchdog.rules.spending-spike")
    record Settings(Set<Category> categories, double minStdDevs, int minHistoryDays, long minDayTotalCents) {

        Settings {
            Check.require(categories != null && !categories.isEmpty(),
                    "watchdog.rules.spending-spike.categories must list at least one category");
            Check.require(minStdDevs > 0,
                    "watchdog.rules.spending-spike.min-std-devs must be positive, got " + minStdDevs);
            Check.positive("watchdog.rules.spending-spike.min-history-days", minHistoryDays);
            Check.positive("watchdog.rules.spending-spike.min-day-total-cents", minDayTotalCents);
            categories = Set.copyOf(categories);
        }
    }

    /** One card's spending in one category, changed under the engine's per-card lock. */
    private static final class CategoryHistory {
        // Totals of earlier days that had spending in this category. Days with none are left out: otherwise a
        // once-a-week shopper's usual day would be about $5, and every normal trip would look like a spike.
        final RunningStats pastDays = new RunningStats();
        LocalDate today; // null before the first purchase
        long todayCents;
        boolean firedToday;
    }

    private final Settings settings;
    private final Map<String, Map<Category, CategoryHistory>> historyByCard = new ConcurrentHashMap<>();

    SpendingSpikeRule(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "spending-spike";
    }

    @Override
    public IssueType detects() {
        return IssueType.SPENDING_SPIKE;
    }

    @Override
    public Optional<RuleHit> evaluate(Transaction tx) {
        if (!settings.categories().contains(tx.category())) {
            return Optional.empty();
        }
        CategoryHistory history = historyByCard.computeIfAbsent(tx.cardId(), card -> new HashMap<>())
                .computeIfAbsent(tx.category(), category -> new CategoryHistory());

        LocalDate day = LocalDate.ofInstant(tx.occurredAt(), ZoneOffset.UTC); // simulated people live in UTC
        if (history.today == null || day.isAfter(history.today)) {
            if (history.today != null) {
                // Every finished day joins the baseline, spikes included, so the baseline keeps up if spending
                // really changes (decision 9 in docs/DESIGN.md).
                history.pastDays.add(history.todayCents);
            }
            history.today = day;
            history.todayCents = 0;
            history.firedToday = false;
        }
        history.todayCents += tx.amountCents();

        RunningStats past = history.pastDays;
        boolean spike = !history.firedToday
                && past.count() >= settings.minHistoryDays()
                && history.todayCents >= settings.minDayTotalCents()
                && past.standardDeviation() > 0 // a flat history has no "unusual"
                && history.todayCents >= past.mean() + settings.minStdDevs() * past.standardDeviation();
        if (!spike) {
            return Optional.empty();
        }
        history.firedToday = true; // at most once per category per day
        String category = tx.category().name().toLowerCase(Locale.ROOT);
        return Optional.of(hit("You've spent %s on %s today. On a usual %s day this card spends about %s."
                .formatted(Money.format(history.todayCents), category, category,
                        Money.format(Math.round(past.mean())))));
    }
}
