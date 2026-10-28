package io.budget.watchdog.simulator;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * The simulator's clock: simulated time = simulated start + real time elapsed × speed. At 30 simulated days per real
 * minute, one simulated day takes 2 real seconds, so trials and subscription cycles play out in under a minute.
 */
final class SimClock {

    /** Pauses the calling thread. Tests pass a fake one so they don't actually wait. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Instant simStart;
    private final Instant realStart;
    private final double speed; // simulated seconds per real second
    private final InstantSource realClock;
    private final Sleeper sleeper;

    SimClock(Instant simStart, double daysPerMinute, InstantSource realClock, Sleeper sleeper) {
        this.simStart = simStart;
        this.speed = daysPerMinute * 24 * 60; // days per minute -> simulated seconds per real second
        this.realClock = realClock;
        this.sleeper = sleeper;
        this.realStart = realClock.instant();
    }

    /** How much real time remains until the simulated clock reaches {@code simTime}. Always zero at max speed. */
    Duration realDelayUntil(Instant simTime) {
        if (Double.isInfinite(speed)) {
            return Duration.ZERO;
        }
        double simSecondsFromStart = Duration.between(simStart, simTime).toNanos() / 1e9;
        Instant realTarget = realStart.plusNanos(Math.round(simSecondsFromStart / speed * 1e9));
        Duration delay = Duration.between(realClock.instant(), realTarget);
        return delay.isNegative() ? Duration.ZERO : delay;
    }

    /** Waits until the simulated clock reaches {@code simTime}. Returns at once if that moment has already passed. */
    void sleepUntil(Instant simTime) throws InterruptedException {
        Duration delay = realDelayUntil(simTime);
        if (!delay.isZero()) {
            sleeper.sleep(delay);
        }
    }
}
