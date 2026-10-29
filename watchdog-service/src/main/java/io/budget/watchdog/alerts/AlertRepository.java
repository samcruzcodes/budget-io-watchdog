package io.budget.watchdog.alerts;

import io.budget.watchdog.schema.Alert;
import io.budget.watchdog.schema.IssueType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores alerts with plain SQL. The table doubles as the record of which alerts Kafka has confirmed. */
@Repository
public class AlertRepository {

    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Saves new alerts, unpublished. An alert that's already stored (same transaction and detector) is left alone. */
    public void insertAll(List<Alert> alerts) {
        for (Alert alert : alerts) {
            jdbc.sql("""
                            INSERT INTO alerts (alert_id, transaction_id, user_id, card_id, detector, issue_type,
                                                explanation, occurred_at, detected_at)
                            VALUES (:alertId, :transactionId, :userId, :cardId, :detector, :issueType,
                                    :explanation, :occurredAt, :detectedAt)
                            ON CONFLICT (transaction_id, detector) DO NOTHING
                            """)
                    .param("alertId", alert.alertId())
                    .param("transactionId", alert.transactionId())
                    .param("userId", alert.userId())
                    .param("cardId", alert.cardId())
                    .param("detector", alert.detector())
                    .param("issueType", alert.issueType().name())
                    .param("explanation", alert.explanation())
                    .param("occurredAt", utc(alert.occurredAt()))
                    .param("detectedAt", utc(alert.detectedAt()))
                    .update();
        }
    }

    /** A transaction's alerts that Kafka hasn't confirmed yet: left over when publishing failed or the app crashed. */
    public List<Alert> unpublishedFor(UUID transactionId) {
        return jdbc.sql("""
                        SELECT * FROM alerts
                        WHERE transaction_id = :transactionId AND published_at IS NULL
                        ORDER BY detector
                        """)
                .param("transactionId", transactionId)
                .query(AlertRepository::toAlert)
                .list();
    }

    public void markPublished(UUID alertId, Instant publishedAt) {
        jdbc.sql("UPDATE alerts SET published_at = :publishedAt WHERE alert_id = :alertId")
                .param("publishedAt", utc(publishedAt))
                .param("alertId", alertId)
                .update();
    }

    private static Alert toAlert(ResultSet row, int rowNumber) throws SQLException {
        return new Alert(Alert.SCHEMA_VERSION, row.getObject("alert_id", UUID.class),
                row.getObject("transaction_id", UUID.class), row.getString("user_id"), row.getString("card_id"),
                row.getString("detector"), IssueType.valueOf(row.getString("issue_type")),
                row.getString("explanation"), row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("detected_at", OffsetDateTime.class).toInstant());
    }

    /** The PostgreSQL driver maps OffsetDateTime (not Instant) onto a TIMESTAMPTZ column. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
