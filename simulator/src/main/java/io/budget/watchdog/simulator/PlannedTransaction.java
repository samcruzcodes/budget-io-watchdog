package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.Transaction;
import java.time.Instant;
import java.util.UUID;

/** A transaction the simulator has planned, with its label. {@code publishedAt} is stamped only when it is sent. */
record PlannedTransaction(
        UUID transactionId,
        String userId,
        String cardId,
        Persona persona,
        Instant occurredAt,
        String merchant,
        Category category,
        long amountCents,
        String country,
        Label label) {

    static final String SOURCE = "SIMULATOR";

    Transaction toTransaction(Instant publishedAt) {
        return new Transaction(Transaction.SCHEMA_VERSION, transactionId, SOURCE, userId, cardId, merchant, category,
                amountCents, "USD", country, occurredAt, publishedAt);
    }

    GroundTruthLabel toGroundTruth() {
        return new GroundTruthLabel(GroundTruthLabel.SCHEMA_VERSION, transactionId, persona.name(),
                label.issueType(), label.scenarioId(), label.edgeCase());
    }

    PlannedTransaction withLabel(Label newLabel) {
        return new PlannedTransaction(transactionId, userId, cardId, persona, occurredAt, merchant, category,
                amountCents, country, newLabel);
    }
}
