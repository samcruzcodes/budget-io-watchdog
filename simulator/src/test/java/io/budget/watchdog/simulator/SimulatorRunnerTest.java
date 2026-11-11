package io.budget.watchdog.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SimulatorRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final Instant SIM_START = SimulationTest.START.atStartOfDay(ZoneOffset.UTC).toInstant();

    @Test
    void publishesEveryTransactionWithItsLabelInTimeOrder() throws InterruptedException {
        SimulatorConfig config = SimulationTest.config(7, 5, SimulatorConfig.Rates.DEFAULTS);
        SimClock clock = new SimClock(SIM_START, Double.POSITIVE_INFINITY, InstantSource.fixed(NOW), false);
        RecordingSink sink = new RecordingSink();
        SimulatorRunner runner = new SimulatorRunner(Simulation.create(config), clock, sink);

        long published = runner.run(SimulationTest.START, 40);

        assertThat(published).isPositive().isEqualTo(sink.transactions.size()).isEqualTo(runner.published());
        assertThat(sink.labels).extracting(GroundTruthLabel::transactionId)
                .containsExactlyElementsOf(sink.transactions.stream().map(Transaction::transactionId).toList());
        assertThat(sink.transactions).extracting(Transaction::occurredAt).isSorted();
    }

    @Test
    void pausingResumingAndChangingSpeedNeverChangeWhatIsPublished() throws Exception {
        SimulatorConfig config = SimulationTest.config(42, 3, SimulatorConfig.Rates.DEFAULTS);
        int days = 60;

        // Straight through, as fast as possible.
        RecordingSink straight = new RecordingSink();
        new SimulatorRunner(Simulation.create(config), new SimClock(SIM_START, Double.POSITIVE_INFINITY,
                InstantSource.fixed(NOW), false), straight).run(SimulationTest.START, days);

        // The same seed on the real clock, while another thread keeps pausing, resuming, and changing the speed at
        // random moments: about 60 simulated days in a second or two of real time.
        SimClock clock = new SimClock(SIM_START, 3_000, InstantSource.system(), false);
        RecordingSink controlled = new RecordingSink();
        CompletableFuture<Long> run = CompletableFuture.supplyAsync(() -> {
            try {
                return new SimulatorRunner(Simulation.create(config), clock, controlled).run(SimulationTest.START, days);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Random random = new Random(1); // the test's own choices, not the simulation's
        double[] speeds = {1_500, 3_000, 6_000, Double.POSITIVE_INFINITY};
        int changes = 0;
        while (!run.isDone()) {
            Thread.sleep(random.nextInt(5, 40));
            switch (random.nextInt(3)) {
                case 0 -> {
                    clock.pause();
                    Thread.sleep(random.nextInt(5, 30));
                    clock.resume();
                }
                case 1 -> clock.setSpeed(speeds[random.nextInt(speeds.length)]);
                default -> clock.setSpeed(3_000);
            }
            changes++;
        }
        run.get(1, TimeUnit.SECONDS);

        assertThat(changes).as("the controls really ran during the simulation").isGreaterThan(5);
        assertThat(controlled.transactions).isEqualTo(straight.transactions); // IDs, times, amounts, merchants
        assertThat(controlled.labels).isEqualTo(straight.labels);
    }

    @Test
    void aRestartedRunContinuesWhereTheFirstStoppedWithTheSameIds() throws InterruptedException {
        SimulatorConfig config = SimulationTest.config(42, 3, SimulatorConfig.Rates.DEFAULTS);
        int days = 60;
        RecordingSink straight = new RecordingSink();
        new SimulatorRunner(Simulation.create(config), maxSpeedClock(SIM_START), straight).run(SimulationTest.START, days);

        // The first process died partway through a day, after publishing this many. The newest one's time is what a
        // restarted simulator finds on the topic (ResumePoint).
        int publishedBeforeTheCrash = straight.transactions.size() / 2;
        Instant resumeFrom = straight.transactions.get(publishedBeforeTheCrash - 1).occurredAt();

        // The second process starts from nothing: new users, new counters, the same seed.
        RecordingSink restarted = new RecordingSink();
        SimulatorRunner runner = new SimulatorRunner(Simulation.create(config), maxSpeedClock(resumeFrom), restarted,
                resumeFrom, 0);
        runner.run(SimulationTest.START, days);

        // Everything the first process hadn't published, with the IDs an uninterrupted run gives them...
        List<Transaction> notYetPublished = straight.transactions.subList(publishedBeforeTheCrash, straight.transactions.size());
        assertThat(restarted.transactions).endsWith(notYetPublished.toArray(Transaction[]::new));
        // ...and nothing from before, except what shares the newest one's exact time (the watchdog drops those).
        List<Transaction> sentAgain = restarted.transactions.subList(0, restarted.transactions.size() - notYetPublished.size());
        assertThat(sentAgain).isNotEmpty().allSatisfy(tx -> assertThat(tx.occurredAt()).isEqualTo(resumeFrom));
        assertThat(runner.progress().resumedFrom()).isEqualTo(resumeFrom);
    }

    @Test
    void pausesItselfEveryNDaysAndPublishesTheSameRun() throws Exception {
        SimulatorConfig config = SimulationTest.config(42, 3, SimulatorConfig.Rates.DEFAULTS);
        int days = 25;
        RecordingSink straight = new RecordingSink();
        new SimulatorRunner(Simulation.create(config), maxSpeedClock(SIM_START), straight).run(SimulationTest.START, days);

        SimClock clock = maxSpeedClock(SIM_START);
        RecordingSink sink = new RecordingSink();
        SimulatorRunner runner = new SimulatorRunner(Simulation.create(config), clock, sink, null, 10);
        CompletableFuture<Long> run = CompletableFuture.supplyAsync(() -> {
            try {
                return runner.run(SimulationTest.START, days);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        // 25 days with a pause every 10: it stops by itself at day 10 and at day 20, and waits to be resumed.
        for (int pause = 1; pause <= 2; pause++) {
            Instant expected = SIM_START.plus(Duration.ofDays(10L * pause));
            awaitPaused(clock);
            assertThat(clock.simulatedNow()).isEqualTo(expected);
            assertThat(runner.progress().pausedItself()).isTrue();
            assertThat(sink.transactions).isNotEmpty()
                    .allSatisfy(tx -> assertThat(tx.occurredAt()).isBefore(expected));
            clock.resume();
            assertThat(runner.progress().pausedItself()).as("resumed, so no longer paused by itself").isFalse();
        }
        run.get(5, TimeUnit.SECONDS);

        assertThat(sink.transactions).isEqualTo(straight.transactions);
    }

    private static SimClock maxSpeedClock(Instant simStart) {
        return new SimClock(simStart, Double.POSITIVE_INFINITY, InstantSource.fixed(NOW), false);
    }

    private static void awaitPaused(SimClock clock) throws InterruptedException {
        for (int tries = 0; tries < 500 && !clock.status().paused(); tries++) {
            Thread.sleep(10);
        }
        assertThat(clock.status().paused()).as("the simulator paused by itself").isTrue();
    }

    /** Keeps what would have been sent to Kafka, with a fixed publishedAt so runs compare equal. */
    private static final class RecordingSink implements TransactionSink {

        // Synchronized, because one test reads them while the run is paused on another thread.
        final List<Transaction> transactions = Collections.synchronizedList(new ArrayList<>());
        final List<GroundTruthLabel> labels = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(PlannedTransaction planned) {
            transactions.add(planned.toTransaction(NOW));
            labels.add(planned.toGroundTruth());
        }

        @Override
        public void close() {
        }
    }
}
