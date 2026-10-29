package io.budget.watchdog.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MonthlyPatternTest {

    private static final Instant START = Instant.parse("2026-03-01T09:00:00Z");

    private final MonthlyPattern monthly = StartingRules.PRICE_HIKE.monthlyPattern();

    @Test
    void monthlyMeans26To35DaysApartInclusive() {
        assertThat(monthly.isMonthly(START, START.plus(Duration.ofDays(26)))).isTrue();
        assertThat(monthly.isMonthly(START, START.plus(Duration.ofDays(35)))).isTrue();
        assertThat(monthly.isMonthly(START, START.plus(Duration.ofDays(26)).minusSeconds(1))).isFalse();
        assertThat(monthly.isMonthly(START, START.plus(Duration.ofDays(35)).plusSeconds(1))).isFalse();
        assertThat(monthly.maxGap()).isEqualTo(Duration.ofDays(35));
    }

    @Test
    void steadyMeansWithinOnePercentEitherWay() {
        assertThat(monthly.isWithinTolerance(10_000, 10_100)).isTrue();
        assertThat(monthly.isWithinTolerance(10_000, 9_900)).isTrue();
        assertThat(monthly.isWithinTolerance(10_000, 10_101)).isFalse();
        assertThat(monthly.isWithinTolerance(10_000, 9_899)).isFalse();
    }

    @Test
    void higherMeansMoreThanOnePercentUp() {
        assertThat(monthly.isHigher(10_000, 10_101)).isTrue();
        assertThat(monthly.isHigher(10_000, 10_100)).isFalse(); // exactly 1% is still steady
        assertThat(monthly.isHigher(1_549, 1_799)).isTrue();
        assertThat(monthly.isHigher(1_549, 1_299)).isFalse();
    }
}
