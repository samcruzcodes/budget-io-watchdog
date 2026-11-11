package io.budget.watchdog.simulator;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * The simulator's clock, which can be paused and sped up while it runs. Simulated time moves from an anchor:
 * simulated now = anchor's simulated moment + real time since the anchor x speed. Every pause, resume, and speed
 * change moves the anchor to the current simulated moment, so simulated time never jumps. At 30 simulated days per
 * real minute, one simulated day takes 2 real seconds.
 *
 * <p>The clock only decides when each planned transaction is sent, never what's planned, so pausing or changing the
 * speed can't change which transactions a seed produces.
 *
 * <p>The publishing thread waits on this object; the control API's thread changes it. Every method is synchronized,
 * and a change calls {@code notifyAll()} so a waiting thread wakes up and works out its wait again.
 */
final class SimClock {

    /** What the clock is doing, for the control API. {@code daysPerMinute} is null at "max" (no waiting). */
    record Status(boolean paused, Double daysPerMinute, Instant simulatedNow) {}

    private final InstantSource realClock;
    private Instant anchorSim;  // the simulated moment at the anchor
    private Instant anchorReal; // the real moment at the anchor
    private double speed;       // simulated seconds per real second; infinite at "max"
    private boolean paused;
    // At "max" speed there's no real-time mapping, so "now" is the last moment the runner reached.
    private Instant lastReached;

    SimClock(Instant simStart, double daysPerMinute, InstantSource realClock, boolean startPaused) {
        this.realClock = realClock;
        this.anchorSim = simStart;
        this.anchorReal = realClock.instant();
        this.speed = toSimSecondsPerSecond(daysPerMinute);
        this.paused = startPaused;
        this.lastReached = simStart;
    }

    /** The current simulated moment. Frozen while paused. */
    synchronized Instant simulatedNow() {
        if (paused) {
            return anchorSim;
        }
        if (Double.isInfinite(speed)) {
            return lastReached;
        }
        double realSeconds = Duration.between(anchorReal, realClock.instant()).toNanos() / 1e9;
        return anchorSim.plusNanos(Math.round(realSeconds * speed * 1e9));
    }

    synchronized void pause() {
        if (!paused) {
            anchorSim = simulatedNow();
            paused = true;
            notifyAll();
        }
    }

    synchronized void resume() {
        if (paused) {
            anchorReal = realClock.instant(); // continue from the frozen simulated moment, starting now
            paused = false;
            notifyAll();
        }
    }

    /** Changes the speed from the current simulated moment on, so there's no jump. */
    synchronized void setSpeed(double daysPerMinute) {
        Instant now = simulatedNow();
        anchorSim = now;
        anchorReal = realClock.instant();
        speed = toSimSecondsPerSecond(daysPerMinute);
        notifyAll();
    }

    synchronized Status status() {
        Double daysPerMinute = Double.isInfinite(speed) ? null : speed / (24 * 60);
        return new Status(paused, daysPerMinute, simulatedNow());
    }

    /**
     * How much real time remains until the simulated clock reaches {@code simTime}, or null while paused (it won't
     * get there until someone resumes). Zero if that moment has passed, and always zero at max speed.
     */
    synchronized Duration realDelayUntil(Instant simTime) {
        if (paused) {
            return null;
        }
        if (Double.isInfinite(speed)) {
            return Duration.ZERO;
        }
        double simSeconds = Duration.between(simulatedNow(), simTime).toNanos() / 1e9;
        return simSeconds <= 0 ? Duration.ZERO : Duration.ofNanos(Math.round(simSeconds / speed * 1e9));
    }

    /**
     * Waits until the simulated clock reaches {@code simTime}. While paused it waits until resumed; a speed change
     * or a resume wakes it to work out the wait again.
     */
    synchronized void awaitSimTime(Instant simTime) throws InterruptedException {
        while (true) {
            Duration delay = realDelayUntil(simTime);
            if (delay == null) {
                wait(); // paused: until resume() or setSpeed() calls notifyAll()
            } else if (delay.isZero()) {
                if (simTime.isAfter(lastReached)) {
                    lastReached = simTime;
                }
                return;
            } else {
                wait(Math.max(1, delay.toMillis()));
            }
        }
    }

    private static double toSimSecondsPerSecond(double daysPerMinute) {
        return daysPerMinute * 24 * 60; // simulated days per real minute -> simulated seconds per real second
    }
}
