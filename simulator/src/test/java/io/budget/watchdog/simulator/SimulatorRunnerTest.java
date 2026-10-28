package io.budget.watchdog.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.Transaction;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatorRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @Test
    void publishesEveryTransactionWithItsLabelInTimeOrder() throws InterruptedException {
        SimulatorConfig config = SimulationTest.config(7, 5, SimulatorConfig.Rates.DEFAULTS);
        SimClock clock = new SimClock(SimulationTest.START.atStartOfDay(ZoneOffset.UTC).toInstant(),
                Double.POSITIVE_INFINITY, InstantSource.fixed(NOW), duration -> { });
        RecordingSink sink = new RecordingSink();

        long published = new SimulatorRunner(Simulation.create(config), clock, sink).run(SimulationTest.START, 40);

        assertThat(published).isPositive().isEqualTo(sink.transactions.size());
        assertThat(sink.labels).extracting(GroundTruthLabel::transactionId)
                .containsExactlyElementsOf(sink.transactions.stream().map(Transaction::transactionId).toList());
        assertThat(sink.transactions).extracting(Transaction::occurredAt).isSorted();
        assertThat(sink.transactions).allSatisfy(tx -> assertThat(tx.publishedAt()).isEqualTo(NOW));
    }

    /** Keeps what would have been sent to Kafka. */
    private static final class RecordingSink implements TransactionSink {

        final List<Transaction> transactions = new ArrayList<>();
        final List<GroundTruthLabel> labels = new ArrayList<>();

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
