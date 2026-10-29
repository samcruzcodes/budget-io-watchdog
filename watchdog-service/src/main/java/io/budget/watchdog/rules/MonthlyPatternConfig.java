package io.budget.watchdog.rules;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Makes price-hike's monthly check, with its configured thresholds, available to the recurring charges list. */
@Configuration
class MonthlyPatternConfig {

    @Bean
    MonthlyPattern monthlyPattern(PriceHikeRule.Settings priceHike) {
        return priceHike.monthlyPattern();
    }
}
