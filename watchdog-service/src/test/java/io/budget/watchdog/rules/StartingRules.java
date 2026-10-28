package io.budget.watchdog.rules;

import io.budget.watchdog.schema.Category;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The starting thresholds from application.yml, for tests that need every rule. RuleWiringTest checks that the YAML
 * binds to exactly these values.
 */
final class StartingRules {

    static final DuplicateChargeRule.Settings DUPLICATE_CHARGE =
            new DuplicateChargeRule.Settings(Duration.ofMinutes(10));
    static final TrialConversionRule.Settings TRIAL_CONVERSION =
            new TrialConversionRule.Settings(100, 5, Duration.ofDays(5), Duration.ofDays(45));
    static final PriceHikeRule.Settings PRICE_HIKE =
            new PriceHikeRule.Settings(Duration.ofDays(26), Duration.ofDays(35), 1);
    static final SpendingSpikeRule.Settings SPENDING_SPIKE =
            new SpendingSpikeRule.Settings(Set.of(Category.DINING, Category.SHOPPING), 3.0, 14, 5_000);
    static final CardTestingRule.Settings CARD_TESTING =
            new CardTestingRule.Settings(Duration.ofMinutes(60), 200, 3, 10_000);
    static final NewCountryRule.Settings NEW_COUNTRY =
            new NewCountryRule.Settings(20, Duration.ofDays(30));

    private StartingRules() {
    }

    /** A new instance of every rule, with empty memory. */
    static List<DetectionRule> fresh() {
        return List.of(
                new DuplicateChargeRule(DUPLICATE_CHARGE),
                new TrialConversionRule(TRIAL_CONVERSION),
                new PriceHikeRule(PRICE_HIKE),
                new SpendingSpikeRule(SPENDING_SPIKE),
                new CardTestingRule(CARD_TESTING),
                new NewCountryRule(NEW_COUNTRY));
    }
}
