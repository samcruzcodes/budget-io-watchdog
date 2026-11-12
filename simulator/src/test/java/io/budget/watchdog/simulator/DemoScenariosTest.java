package io.budget.watchdog.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The shape of each demo run. Whether a run really makes its rule fire is checked against the running stack by the
 * browser tests (ScenariosIT), because the rules live in the watchdog, not here.
 */
class DemoScenariosTest {

    private static final Instant NOW = Instant.parse("2026-06-15T14:30:00Z");

    @ParameterizedTest
    @EnumSource(value = IssueType.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void everyRunIsOneCardsStoryInTimeOrderEndingNow(IssueType type) {
        UUID scenarioId = UUID.randomUUID();

        DemoScenarios.Run run = DemoScenarios.plan(type, scenarioId, NOW);

        assertThat(run.scenarioId()).isEqualTo(scenarioId);
        assertThat(run.userId()).isEqualTo("demo-user");
        assertThat(run.cardId()).isEqualTo("demo-" + scenarioId.toString().substring(0, 8));
        assertThat(run.expectedDetector()).isEqualTo(type.name().toLowerCase().replace('_', '-'));
        assertThat(run.steps()).extracting(DemoScenarios.Step::occurredAt).isSorted();
        assertThat(run.steps().getLast().occurredAt()).isEqualTo(NOW);
        assertThat(run.steps().getLast().role()).isEqualTo(DemoScenarios.Role.SUSPICIOUS);
        assertThat(run.steps()).extracting(DemoScenarios.Step::transactionId).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @EnumSource(value = IssueType.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void everyMessageIsMarkedAsDemoAndCarriesTheScenarioId(IssueType type) {
        DemoScenarios.Run run = DemoScenarios.plan(type, UUID.randomUUID(), NOW);

        for (DemoScenarios.Step step : run.steps()) {
            Transaction transaction = run.transaction(step, NOW);
            GroundTruthLabel label = run.label(step);
            assertThat(transaction.source()).isEqualTo(Transaction.DEMO_SOURCE);
            assertThat(transaction.cardId()).isEqualTo(run.cardId());
            assertThat(label.transactionId()).isEqualTo(transaction.transactionId());
            assertThat(label.scenarioId()).isEqualTo(run.scenarioId());
            assertThat(label.persona()).isEqualTo("DEMO");
            // The answer key names the issue on the suspicious steps only.
            assertThat(label.issueType())
                    .isEqualTo(step.role() == DemoScenarios.Role.SUSPICIOUS ? type : IssueType.NONE);
        }
    }

    @Test
    void runningAScenarioAgainNeverReusesAnId() {
        Set<UUID> transactionIds = new HashSet<>();
        Set<String> cards = new HashSet<>();
        int transactions = 0;
        for (int run = 0; run < 50; run++) {
            DemoScenarios.Run again = DemoScenarios.plan(IssueType.DUPLICATE_CHARGE, UUID.randomUUID(), NOW);
            cards.add(again.cardId());
            for (DemoScenarios.Step step : again.steps()) {
                transactionIds.add(step.transactionId());
                transactions++;
            }
        }

        // The watchdog stores each transaction ID once, so a repeated ID would be skipped and the rerun would show nothing.
        assertThat(transactionIds).hasSize(transactions);
        assertThat(cards).hasSize(50);
    }

    @Test
    void theSpendingSpikeStaysInsideOneDayEvenJustAfterMidnight() {
        Instant justAfterMidnight = Instant.parse("2026-06-15T00:07:00Z");

        DemoScenarios.Run run = DemoScenarios.plan(IssueType.SPENDING_SPIKE, UUID.randomUUID(), justAfterMidnight);

        // The rule adds up each UTC day. A purchase dated yesterday evening would split the binge over two days.
        assertThat(run.steps()).filteredOn(step -> step.role() == DemoScenarios.Role.SUSPICIOUS)
                .hasSize(3)
                .allSatisfy(step -> assertThat(step.occurredAt().atZone(ZoneOffset.UTC).toLocalDate())
                        .isEqualTo(justAfterMidnight.atZone(ZoneOffset.UTC).toLocalDate()));
        // And each earlier day has exactly one ordinary purchase.
        assertThat(run.steps()).filteredOn(step -> step.role() == DemoScenarios.Role.SETUP)
                .extracting(step -> step.occurredAt().atZone(ZoneOffset.UTC).toLocalDate())
                .doesNotHaveDuplicates().hasSize(16);
    }

    @Test
    void noneIsNotAScenario() {
        assertThatThrownBy(() -> DemoScenarios.plan(IssueType.NONE, UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRunnerPublishesTheRunAtTheSimulatedMomentAndWorksWhilePaused() {
        Instant simStart = Instant.parse("2027-03-01T09:00:00Z");
        SimClock clock = new SimClock(simStart, 30, InstantSource.fixed(NOW), true); // paused from the start
        List<Transaction> published = new ArrayList<>();
        List<GroundTruthLabel> labels = new ArrayList<>();
        UUID scenarioId = UUID.fromString("0a1daee7-06a7-42e1-ab56-5f78b04cce84");
        DemoRunner runner = new DemoRunner(true, clock, (transaction, label) -> {
            published.add(transaction);
            labels.add(label);
        }, InstantSource.fixed(NOW), () -> scenarioId);

        DemoScenarios.Run run = runner.run(IssueType.PRICE_HIKE);

        assertThat(published).hasSize(3).allSatisfy(tx -> assertThat(tx.publishedAt()).isEqualTo(NOW));
        assertThat(published).extracting(Transaction::occurredAt)
                .containsExactly(simStart.minus(Duration.ofDays(60)), simStart.minus(Duration.ofDays(30)), simStart);
        assertThat(published).extracting(Transaction::amountCents).containsExactly(1_549L, 1_549L, 1_849L);
        assertThat(labels).extracting(GroundTruthLabel::scenarioId).containsOnly(scenarioId);
        assertThat(run.cardId()).isEqualTo("demo-0a1daee7");
    }

    @Test
    void theRunnerRefusesWhenScenariosAreOff() {
        SimClock clock = new SimClock(NOW, 30, InstantSource.fixed(NOW), false);
        DemoRunner runner = new DemoRunner(false, clock, (transaction, label) -> {
            throw new AssertionError("nothing may be published");
        }, InstantSource.fixed(NOW), UUID::randomUUID);

        assertThatThrownBy(() -> runner.run(IssueType.CARD_TESTING)).isInstanceOf(IllegalStateException.class);
    }
}
