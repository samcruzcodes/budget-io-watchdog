package io.budget.watchdog.simulator;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Plays the simulation forward one day at a time, publishing each transaction when the simulated clock reaches it. */
final class SimulatorRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    private final Simulation simulation;
    private final SimClock clock;
    private final TransactionSink sink;

    SimulatorRunner(Simulation simulation, SimClock clock, TransactionSink sink) {
        this.simulation = simulation;
        this.clock = clock;
        this.sink = sink;
    }

    /**
     * @param days how many simulated days to run; 0 runs until the thread is interrupted
     * @return how many transactions were published
     */
    long run(LocalDate startDate, int days) throws InterruptedException {
        long published = 0;
        for (LocalDate day = startDate; days == 0 || day.isBefore(startDate.plusDays(days)); day = day.plusDays(1)) {
            if (Thread.interrupted()) {
                throw new InterruptedException("stop requested");
            }
            for (PlannedTransaction transaction : simulation.planDay(day)) {
                clock.sleepUntil(transaction.occurredAt());
                sink.publish(transaction);
                published++;
            }
            if (day.getDayOfMonth() == 1) {
                log.info("Simulated through {}: {} transactions published", day, published);
            }
        }
        return published;
    }
}
