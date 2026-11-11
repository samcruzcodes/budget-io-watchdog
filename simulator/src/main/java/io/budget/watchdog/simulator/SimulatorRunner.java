package io.budget.watchdog.simulator;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays the simulation forward one day at a time, publishing each transaction when the simulated clock reaches it.
 * Days are always planned in order, whatever the clock does, so pausing or changing speed only changes when each
 * transaction is sent, never which ones there are.
 *
 * <p>Two optional extras, both off unless asked for:
 * <ul>
 *   <li><b>Continuing an earlier run</b> ({@code resumeFrom}): every day is still planned from the start date,
 *       because planning is what moves each user's state and ID counters forward, but transactions before
 *       {@code resumeFrom} aren't sent again. The ones at exactly that moment are: the earlier run may have stopped
 *       between two of them, and the watchdog drops whichever it already has.</li>
 *   <li><b>Pausing by itself</b> ({@code pauseAfterDays}): after that many simulated days the clock pauses, and again
 *       that many days after each time, so a demo left running can't fill Kafka and Postgres.</li>
 * </ul>
 */
final class SimulatorRunner {

    /** What the control API reports about the run, besides the clock. {@code resumedFrom} is null for a new run. */
    record Progress(long published, Instant resumedFrom, int pauseAfterDays, boolean pausedItself) {}

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    private final Simulation simulation;
    private final SimClock clock;
    private final TransactionSink sink;
    private final Instant resumeFrom; // null: publish everything
    private final int pauseAfterDays; // 0: never pause by itself
    private final AtomicLong published = new AtomicLong(); // read by the control API's thread
    private volatile Instant pausedItselfAt;               // also read by the control API's thread

    /** A new run that never pauses by itself. */
    SimulatorRunner(Simulation simulation, SimClock clock, TransactionSink sink) {
        this(simulation, clock, sink, null, 0);
    }

    SimulatorRunner(Simulation simulation, SimClock clock, TransactionSink sink, Instant resumeFrom,
            int pauseAfterDays) {
        this.simulation = simulation;
        this.clock = clock;
        this.sink = sink;
        this.resumeFrom = resumeFrom;
        this.pauseAfterDays = pauseAfterDays;
    }

    /**
     * @param days how many simulated days to run, counted from {@code startDate}; 0 runs until the thread is interrupted
     * @return how many transactions were published
     */
    long run(LocalDate startDate, int days) throws InterruptedException {
        Instant nextPause = pauseAfterDays == 0 ? null
                : firstMoment(startDate, resumeFrom).plus(Duration.ofDays(pauseAfterDays));
        long skipped = 0;

        for (LocalDate day = startDate; days == 0 || day.isBefore(startDate.plusDays(days)); day = day.plusDays(1)) {
            if (Thread.interrupted()) {
                throw new InterruptedException("stop requested");
            }
            for (PlannedTransaction transaction : simulation.planDay(day)) {
                if (resumeFrom != null && transaction.occurredAt().isBefore(resumeFrom)) {
                    skipped++; // an earlier run already published it
                    continue;
                }
                if (skipped > 0 && published.get() == 0) {
                    log.info("Continuing at {}: skipped {} transactions an earlier run already published", resumeFrom,
                            skipped);
                }
                if (nextPause != null && !transaction.occurredAt().isBefore(nextPause)) {
                    clock.awaitSimTime(nextPause);
                    clock.pause();
                    pausedItselfAt = clock.simulatedNow(); // frozen while paused
                    log.info("Paused after {} simulated days, at {}. Resume from the dashboard to continue.",
                            pauseAfterDays, pausedItselfAt);
                    nextPause = nextPause.plus(Duration.ofDays(pauseAfterDays));
                }
                clock.awaitSimTime(transaction.occurredAt()); // while paused, this waits until someone resumes
                sink.publish(transaction);
                published.incrementAndGet();
            }
            if (day.getDayOfMonth() == 1 && published.get() > 0) {
                log.info("Simulated through {}: {} transactions published", day, published.get());
            }
        }
        return published.get();
    }

    /** Where this run's simulated clock should start: the start date, or the later moment it continues from. */
    static Instant firstMoment(LocalDate startDate, Instant resumeFrom) {
        Instant start = startDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        return resumeFrom != null && resumeFrom.isAfter(start) ? resumeFrom : start;
    }

    /** How many transactions this process has published so far. */
    long published() {
        return published.get();
    }

    Progress progress() {
        // "Paused itself" stops being true once someone resumes: simulated time moves on from that moment.
        Instant pausedAt = pausedItselfAt;
        SimClock.Status status = clock.status();
        boolean pausedItself = pausedAt != null && status.paused() && status.simulatedNow().equals(pausedAt);
        return new Progress(published.get(), resumeFrom, pauseAfterDays, pausedItself);
    }
}
