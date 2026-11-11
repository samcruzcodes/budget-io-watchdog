package io.budget.watchdog.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SimClockTest {

    private static final Instant SIM_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REAL_START = Instant.parse("2026-10-27T12:00:00Z");

    private Instant realNow = REAL_START;
    private final InstantSource realClock = () -> realNow; // tests move real time by hand

    private SimClock clock(double daysPerMinute) {
        return new SimClock(SIM_START, daysPerMinute, realClock, false);
    }

    private void realSecondsPass(double seconds) {
        realNow = realNow.plusMillis(Math.round(seconds * 1000));
    }

    @Test
    void atThirtyDaysPerMinuteOneSimulatedDayTakesTwoRealSeconds() {
        SimClock clock = clock(30);

        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(1)))).isEqualTo(Duration.ofSeconds(2));
        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(30)))).isEqualTo(Duration.ofMinutes(1));
        realSecondsPass(2);
        assertThat(clock.simulatedNow()).isEqualTo(SIM_START.plus(Duration.ofDays(1)));
    }

    @Test
    void momentsThatHaveAlreadyPassedDontWait() {
        SimClock clock = clock(30);
        realSecondsPass(10);

        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(1)))).isZero();
    }

    @Test
    void pausingFreezesSimulatedTime() {
        SimClock clock = clock(30);
        realSecondsPass(2); // one simulated day

        clock.pause();
        realSecondsPass(60); // a whole real minute passes while paused

        assertThat(clock.simulatedNow()).isEqualTo(SIM_START.plus(Duration.ofDays(1)));
        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(2)))).isNull(); // won't get there while paused
        assertThat(clock.status().paused()).isTrue();
    }

    @Test
    void resumingContinuesFromTheSameSimulatedMoment() {
        SimClock clock = clock(30);
        realSecondsPass(2);
        clock.pause();
        realSecondsPass(60);

        clock.resume();

        assertThat(clock.simulatedNow()).isEqualTo(SIM_START.plus(Duration.ofDays(1))); // no jump
        realSecondsPass(2);
        assertThat(clock.simulatedNow()).isEqualTo(SIM_START.plus(Duration.ofDays(2)));
    }

    @Test
    void changingSpeedContinuesFromTheCurrentMomentWithoutAJump() {
        SimClock clock = clock(30);
        realSecondsPass(2); // day 1

        clock.setSpeed(1); // one simulated day per real minute

        assertThat(clock.simulatedNow()).isEqualTo(SIM_START.plus(Duration.ofDays(1)));
        realSecondsPass(60);
        assertThat(clock.simulatedNow()).isEqualTo(SIM_START.plus(Duration.ofDays(2)));
        assertThat(clock.status().daysPerMinute()).isEqualTo(1.0);
    }

    @Test
    void changingSpeedWhilePausedStaysPaused() {
        SimClock clock = clock(30);
        clock.pause();

        clock.setSpeed(365);
        realSecondsPass(10);

        assertThat(clock.simulatedNow()).isEqualTo(SIM_START);
        assertThat(clock.status().paused()).isTrue();
    }

    @Test
    void canStartPaused() {
        SimClock clock = new SimClock(SIM_START, 30, realClock, true);
        realSecondsPass(60);

        assertThat(clock.simulatedNow()).isEqualTo(SIM_START);
    }

    @Test
    void maxSpeedNeverWaitsAndReportsTheLastMomentReached() throws InterruptedException {
        SimClock clock = clock(Double.POSITIVE_INFINITY);

        assertThat(clock.realDelayUntil(SIM_START.plus(Duration.ofDays(365)))).isZero();
        clock.awaitSimTime(SIM_START.plus(Duration.ofDays(3)));
        assertThat(clock.simulatedNow()).isEqualTo(SIM_START.plus(Duration.ofDays(3)));
        assertThat(clock.status().daysPerMinute()).isNull();
    }

    @Test
    void aWaitThatIsPausedFinishesSoonAfterResume() throws Exception {
        // Real time here, on a real thread: the point is that resume() wakes a thread that's waiting.
        SimClock clock = new SimClock(SIM_START, 30 * 24 * 60, InstantSource.system(), true); // a simulated day per real ms
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
            try {
                clock.awaitSimTime(SIM_START.plus(Duration.ofDays(1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(200);
        assertThat(waiting).isNotDone(); // paused: it doesn't finish on its own

        clock.resume();
        waiting.get(2, TimeUnit.SECONDS);
    }

    @Test
    void aSpeedChangeWakesALongWaitSoItCanFinishSooner() throws Exception {
        // At 1 simulated day per real minute, a day away is a minute of waiting; at max speed it's none.
        SimClock clock = new SimClock(SIM_START, 1, InstantSource.system(), false);
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
            try {
                clock.awaitSimTime(SIM_START.plus(Duration.ofDays(1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(200);
        clock.setSpeed(Double.POSITIVE_INFINITY);
        waiting.get(2, TimeUnit.SECONDS);
    }
}
