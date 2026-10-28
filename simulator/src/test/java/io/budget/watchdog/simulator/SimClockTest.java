package io.budget.watchdog.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimClockTest {

    private static final Instant SIM_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REAL_START = Instant.parse("2026-10-27T12:00:00Z");

    private Instant realNow = REAL_START;
    private final InstantSource realClock = () -> realNow;

    @Test
    void atThirtyDaysPerMinuteOneSimulatedDayTakesTwoRealSeconds() {
        SimClock clock = new SimClock(SIM_START, 30, realClock, duration -> { });

        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(1)))).isEqualTo(Duration.ofSeconds(2));
        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(30)))).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void realTimeThatHasAlreadyPassedCountsTowardTheWait() {
        SimClock clock = new SimClock(SIM_START, 30, realClock, duration -> { });

        realNow = REAL_START.plusMillis(1_500);

        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(1)))).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void momentsThatHaveAlreadyPassedDontWait() {
        SimClock clock = new SimClock(SIM_START, 30, realClock, duration -> { });

        realNow = REAL_START.plusSeconds(10);

        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(1)))).isZero();
    }

    @Test
    void maxSpeedNeverWaits() {
        SimClock clock = new SimClock(SIM_START, Double.POSITIVE_INFINITY, realClock, duration -> { });

        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(365)))).isZero();
    }

    @Test
    void sleepUntilSleepsOnlyForTheRemainingDelay() throws InterruptedException {
        List<Duration> sleeps = new ArrayList<>();
        SimClock clock = new SimClock(SIM_START, 30, realClock, sleeps::add);

        clock.sleepUntil(SIM_START.plus(Duration.ofHours(12))); // half a simulated day: one real second
        clock.sleepUntil(SIM_START);                             // already passed: no sleep

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }
}
