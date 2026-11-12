package io.budget.watchdog.alerts;

import io.budget.watchdog.schema.Alert;
import io.budget.watchdog.schema.IssueType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores and reads alerts with plain SQL. The table doubles as the record of which alerts Kafka has confirmed. */
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

    /** The newest alerts, by event time, optionally for one user and one detector (null means any). */
    public List<Alert> recent(String userId, String detector, int limit) {
        return jdbc.sql("""
                        SELECT * FROM alerts
                        WHERE (CAST(:userId AS text) IS NULL OR user_id = :userId)
                          AND (CAST(:detector AS text) IS NULL OR detector = :detector)
                        ORDER BY occurred_at DESC, detector
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("detector", detector)
                .param("limit", limit)
                .query(AlertRepository::toAlert)
                .list();
    }

    /** Every alert raised on any of these transactions, in the order the transactions happened. */
    public List<Alert> forTransactions(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return List.of(); // "IN ()" isn't valid SQL
        }
        return jdbc.sql("""
                        SELECT * FROM alerts
                        WHERE transaction_id IN (:ids)
                        ORDER BY occurred_at, detector
                        """)
                .param("ids", transactionIds)
                .query(AlertRepository::toAlert)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM alerts").query(Long.class).single();
    }

    /** Alerts per UTC day of their transaction's event time, in [start, end); days with none are left out. */
    public Map<LocalDate, Long> dailyCounts(Instant start, Instant end) {
        Map<LocalDate, Long> counts = new TreeMap<>();
        jdbc.sql("""
                        SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, count(*) AS alerts
                        FROM alerts
                        WHERE occurred_at >= :start AND occurred_at < :end
                        GROUP BY 1
                        """)
                .param("start", utc(start))
                .param("end", utc(end))
                .query((RowCallbackHandler) row -> counts.put(row.getObject("day", LocalDate.class),
                        row.getLong("alerts")));
        return counts;
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
