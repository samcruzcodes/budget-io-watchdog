package io.budget.watchdog.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.IssueType;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Checks what Spring builds from the rules package and the real application.yml, the same way the app does at
 * startup, but with no Kafka and no Postgres.
 */
class RuleWiringTest {

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = DetectionRule.class)
    @ConfigurationPropertiesScan(basePackageClasses = DetectionRule.class)
    static class RulesOnly {
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer()) // loads application.yml
            .withUserConfiguration(RulesOnly.class);

    @Test
    void loadsEveryRuleWithItsStartingThresholds() {
        context.run(spring -> {
            assertThat(spring).hasNotFailed().hasSingleBean(RuleEngine.class);
            assertThat(spring.getBeansOfType(DetectionRule.class).values())
                    .extracting(DetectionRule::id)
                    .containsExactlyInAnyOrder(
                            "duplicate-charge", "trial-conversion", "price-hike", "spending-spike", "card-testing",
                            "new-country");
            // Each issue type is scored against exactly one rule, so a new issue type without a rule fails here.
            assertThat(spring.getBeansOfType(DetectionRule.class).values())
                    .extracting(DetectionRule::detects)
                    .containsExactlyInAnyOrderElementsOf(EnumSet.complementOf(EnumSet.of(IssueType.NONE)));
            assertThat(spring.getBean(DuplicateChargeRule.Settings.class)).isEqualTo(StartingRules.DUPLICATE_CHARGE);
            assertThat(spring.getBean(TrialConversionRule.Settings.class)).isEqualTo(StartingRules.TRIAL_CONVERSION);
            assertThat(spring.getBean(PriceHikeRule.Settings.class)).isEqualTo(StartingRules.PRICE_HIKE);
            assertThat(spring.getBean(SpendingSpikeRule.Settings.class)).isEqualTo(StartingRules.SPENDING_SPIKE);
            assertThat(spring.getBean(CardTestingRule.Settings.class)).isEqualTo(StartingRules.CARD_TESTING);
            assertThat(spring.getBean(NewCountryRule.Settings.class)).isEqualTo(StartingRules.NEW_COUNTRY);
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            watchdog.rules.duplicate-charge.window=-1m      | duplicate-charge.window must be positive
            watchdog.rules.trial-conversion.min-delay=50d   | trial-conversion.min-delay must be less than max-delay
            watchdog.rules.spending-spike.categories=       | spending-spike.categories must list at least one category
            """)
    void badSettingsStopStartupWithAMessageNamingTheSetting(String setting, String message) {
        context.withPropertyValues(setting).run(spring -> {
            assertThat(spring).hasFailed();
            assertThat(spring.getStartupFailure()).rootCause().hasMessageContaining(message);
        });
    }
}
