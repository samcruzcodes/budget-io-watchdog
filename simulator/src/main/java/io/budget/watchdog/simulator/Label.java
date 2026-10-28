package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.EdgeCase;
import io.budget.watchdog.schema.IssueType;
import java.util.UUID;

/** The answer-key part of a planned transaction: what it really is (see Ground-truth labels in docs/DESIGN.md). */
record Label(IssueType issueType, UUID scenarioId, EdgeCase edgeCase) {

    /** An ordinary transaction. */
    static final Label NORMAL = new Label(IssueType.NONE, null, null);

    /** An injected issue, such as the second charge of a duplicate. */
    static Label issue(IssueType issueType, UUID scenarioId) {
        return new Label(issueType, scenarioId, null);
    }

    /** A legitimate transaction that looks like an issue, such as a second coffee bought for a friend. */
    static Label edgeCase(EdgeCase edgeCase, UUID scenarioId) {
        return new Label(IssueType.NONE, scenarioId, edgeCase);
    }

    /** A normal transaction that belongs to a scenario, such as the original of a duplicated charge. */
    static Label partOf(UUID scenarioId) {
        return new Label(IssueType.NONE, scenarioId, null);
    }
}
