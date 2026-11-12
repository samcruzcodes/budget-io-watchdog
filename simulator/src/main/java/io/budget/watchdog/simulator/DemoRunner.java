package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.time.Instant;
import java.time.InstantSource;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a demo scenario when the control API asks for one: plans it at the current simulated moment and publishes its
 * transactions and labels to Kafka, the same way the seeded simulation's are published. From there on nothing knows
 * it's a demo except the {@code source} field and the label's scenario ID: the watchdog consumes, stores, and checks
 * these transactions like any others.
 *
 * <p>It works while the simulator is paused too, which makes for a quieter demo. It's off unless
 * {@code SIM_SCENARIOS_ENABLED=true}: anything that can reach this API could otherwise inject transactions.
 */
final class DemoRunner {

    /** Where a run's messages go: Kafka in production, a list in tests. */
    interface Publisher {
        void publish(Transaction transaction, GroundTruthLabel label);
    }

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final boolean enabled;
    private final SimClock clock;
    private final Publisher publisher;
    private final InstantSource realClock;
    private final Supplier<UUID> scenarioIds;

    /**
     * @param scenarioIds a new ID per run. Random in production, on purpose: a run happens when a person clicks, so
     *                    it was never part of the repeatable seeded stream, and a counter would start over (and repeat
     *                    IDs the watchdog has stored) every time the simulator restarts
     */
    DemoRunner(boolean enabled, SimClock clock, Publisher publisher, InstantSource realClock,
            Supplier<UUID> scenarioIds) {
        this.enabled = enabled;
        this.clock = clock;
        this.publisher = publisher;
        this.realClock = realClock;
        this.scenarioIds = scenarioIds;
    }

    boolean enabled() {
        return enabled;
    }

    /** Publishes one run of {@code type} and returns what was sent, so the caller can follow it by scenario ID. */
    DemoScenarios.Run run(IssueType type) {
        if (!enabled) {
            throw new IllegalStateException("demo scenarios are turned off");
        }
        Instant simulatedNow = clock.simulatedNow();
        DemoScenarios.Run run = DemoScenarios.plan(type, scenarioIds.get(), simulatedNow);
        for (DemoScenarios.Step step : run.steps()) {
            // publishedAt is stamped per message, like the seeded stream, so latency is measured the same way.
            publisher.publish(run.transaction(step, realClock.instant()), run.label(step));
        }
        log.info("Demo scenario {} ({}): published {} transactions on {} at simulated {}", type, run.scenarioId(),
                run.steps().size(), run.cardId(), simulatedNow);
        return run;
    }
}
