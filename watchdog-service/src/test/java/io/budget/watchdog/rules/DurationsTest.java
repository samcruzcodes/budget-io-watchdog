package io.budget.watchdog.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DurationsTest {

    @ParameterizedTest(name = "{0} s is \"{1}\"")
    @CsvSource({
            "1,    1 second",
            "40,   40 seconds",
            "119,  119 seconds",
            "120,  2 minutes",
            "179,  2 minutes",
            "600,  10 minutes"})
    void secondsUnderTwoMinutesThenWholeMinutes(long seconds, String expected) {
        assertThat(Durations.describe(Duration.ofSeconds(seconds))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} h is \"{1}\"")
    @CsvSource({
            "24,   1 day",
            "47,   1 day",
            "311,  12 days",
            "720,  30 days"})
    void wholeDaysRoundedDown(long hours, String expected) {
        assertThat(Durations.days(Duration.ofHours(hours))).isEqualTo(expected);
    }
}
