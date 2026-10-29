package io.budget.watchdog.schema;

import static io.budget.watchdog.schema.Validation.hasText;
import static io.budget.watchdog.schema.Validation.require;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Alert v1: a detector flagged a transaction. Published to {@code alerts}, keyed by cardId, and stored in the
 * watchdog's {@code alerts} table. Publishing is at-least-once, so a rare duplicate message is possible: anything that
 * reads alerts should drop duplicates by alertId.
 *
 * @param alertId     the same for the same transaction and detector, so a republished alert keeps its ID (see
 *                    {@link #idFor})
 * @param detector    what raised it: a rule's ID like "duplicate-charge" today, a model's name later
 * @param explanation plain English for the cardholder
 * @param occurredAt  the transaction's event time
 * @param detectedAt  real time the watchdog raised the alert
 */
public record Alert(
        int schemaVersion,
        UUID alertId,
        UUID transactionId,
        String userId,
        String cardId,
        String detector,
        IssueType issueType,
        String explanation,
        Instant occurredAt,
        Instant detectedAt) {

    public static final int SCHEMA_VERSION = 1;

    public Alert {
        require(schemaVersion == SCHEMA_VERSION, "unsupported schemaVersion: " + schemaVersion);
        require(alertId != null, "alertId is required");
        require(transactionId != null, "transactionId is required");
        require(hasText(userId), "userId is required");
        require(hasText(cardId), "cardId is required");
        require(hasText(detector), "detector is required");
        require(issueType != null && issueType != IssueType.NONE, "an alert needs the issue type it detects, not " + issueType);
        require(hasText(explanation), "explanation is required");
        require(occurredAt != null, "occurredAt is required");
        require(detectedAt != null, "detectedAt is required");
    }

    /** A new alert for a transaction, with its ID derived from the transaction and the detector. */
    public static Alert of(Transaction tx, String detector, IssueType issueType, String explanation, Instant detectedAt) {
        return new Alert(SCHEMA_VERSION, idFor(tx.transactionId(), detector), tx.transactionId(), tx.userId(),
                tx.cardId(), detector, issueType, explanation, tx.occurredAt(), detectedAt);
    }

    /**
     * A name-based UUID: the same transaction and detector always give the same ID, so a retry or a rerun of the same
     * seed can't make a new one.
     */
    public static UUID idFor(UUID transactionId, String detector) {
        return UUID.nameUUIDFromBytes((transactionId + "/" + detector).getBytes(StandardCharsets.UTF_8));
    }
}
