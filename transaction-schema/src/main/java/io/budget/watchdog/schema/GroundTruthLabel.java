package io.budget.watchdog.schema;

import static io.budget.watchdog.schema.Validation.hasText;
import static io.budget.watchdog.schema.Validation.require;

import java.util.UUID;

/**
 * Ground-truth label v1: the simulator's answer key, one per simulated transaction, published to
 * {@code transaction-labels} keyed by transactionId. Only evaluation code may read labels; detection
 * rules never do. Field meanings are in docs/DESIGN.md, under Ground-truth labels.
 *
 * @param scenarioId groups the transactions of one injected issue or edge case; null for ordinary transactions
 * @param edgeCase   the legitimate look-alike this transaction belongs to, or null
 */
public record GroundTruthLabel(
        int schemaVersion,
        UUID transactionId,
        String persona,
        IssueType issueType,
        UUID scenarioId,
        EdgeCase edgeCase) {

    public static final int SCHEMA_VERSION = 1;

    public GroundTruthLabel {
        require(schemaVersion == SCHEMA_VERSION, "unsupported schemaVersion: " + schemaVersion);
        require(transactionId != null, "transactionId is required");
        require(hasText(persona), "persona is required");
        require(issueType != null, "issueType is required (NONE for a normal transaction)");
        require(issueType == IssueType.NONE || scenarioId != null, "an injected issue needs a scenarioId");
        require(edgeCase == null || scenarioId != null, "an edge case needs a scenarioId");
        require(edgeCase == null || issueType == IssueType.NONE, "an edge case is legitimate, so its issueType must be NONE");
    }
}
