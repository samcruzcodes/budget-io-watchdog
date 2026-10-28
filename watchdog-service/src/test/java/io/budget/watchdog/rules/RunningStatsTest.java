package io.budget.watchdog.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RunningStatsTest {

    @Test
    void matchesADirectTwoPassCalculation() {
        double[] values = {2, 4, 4, 4, 5, 5, 7, 9};
        RunningStats stats = statsOf(values);

        // Two passes: the mean first, then the squared distances from it.
        double mean = Arrays.stream(values).average().orElseThrow();
        double sumOfSquares = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum();
        double standardDeviation = Math.sqrt(sumOfSquares / (values.length - 1));

        assertThat(stats.count()).isEqualTo(8);
        assertThat(stats.mean()).isCloseTo(5.0, within(1e-12));
        assertThat(stats.standardDeviation()).isCloseTo(standardDeviation, within(1e-12))
                .isCloseTo(2.138, within(0.001));
    }

    @Test
    void noValuesOrOneValueHasNoSpread() {
        assertThat(new RunningStats().standardDeviation()).isZero();

        RunningStats one = statsOf(4_200);
        assertThat(one.mean()).isEqualTo(4_200);
        assertThat(one.standardDeviation()).isZero();
    }

    @Test
    void staysAccurateForValuesNearABillion() {
        double[] values = {1e9 + 4, 1e9 + 7, 1e9 + 13, 1e9 + 16}; // the spread of 4, 7, 13, 16: variance exactly 30

        assertThat(statsOf(values).standardDeviation()).isCloseTo(Math.sqrt(30), within(1e-9));

        // The sum-of-squares shortcut on the same values: the squares are around 10^18, past what a double holds
        // exactly, so the spread rounds away.
        double sum = Arrays.stream(values).sum();
        double sumOfSquares = Arrays.stream(values).map(v -> v * v).sum();
        double shortcutVariance = (sumOfSquares - sum * sum / values.length) / (values.length - 1);
        assertThat(shortcutVariance).isNotCloseTo(30, within(1.0));
    }

    private static RunningStats statsOf(double... values) {
        RunningStats stats = new RunningStats();
        for (double value : values) {
            stats.add(value);
        }
        return stats;
    }
}
