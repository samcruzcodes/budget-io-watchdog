package io.budget.watchdog.ingest;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.Transaction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores transactions with plain SQL through Spring's JdbcClient. */
@Repository
public class TransactionRepository {

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the transaction unless one with the same ID is already stored.
     *
     * @return true if it was new; false if Kafka delivered a message we had already stored
     */
    public boolean insertIfAbsent(Transaction tx, Instant processedAt) {
        int inserted = jdbc.sql("""
                        INSERT INTO transactions (transaction_id, source, user_id, card_id, merchant, category,
                                                  amount_cents, currency, country, occurred_at, published_at, processed_at)
                        VALUES (:transactionId, :source, :userId, :cardId, :merchant, :category,
                                :amountCents, :currency, :country, :occurredAt, :publishedAt, :processedAt)
                        ON CONFLICT (transaction_id) DO NOTHING
                        """)
                .param("transactionId", tx.transactionId())
                .param("source", tx.source())
                .param("userId", tx.userId())
                .param("cardId", tx.cardId())
                .param("merchant", tx.merchant())
                .param("category", tx.category().name())
                .param("amountCents", tx.amountCents())
                .param("currency", tx.currency())
                .param("country", tx.country())
                .param("occurredAt", utc(tx.occurredAt()))
                .param("publishedAt", utc(tx.publishedAt()))
                .param("processedAt", utc(processedAt))
                .update();
        return inserted == 1;
    }

    /** The event time of the newest stored transaction, or empty if nothing is stored yet. */
    public Optional<Instant> newestOccurredAt() {
        return jdbc.sql("SELECT occurred_at FROM transactions ORDER BY occurred_at DESC LIMIT 1")
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Hands every transaction that occurred at or after {@code from} to {@code action}, card by card, each card in the
     * order it was stored (the order the live rules saw it). Rows are streamed a batch at a time instead of loaded all
     * at once; PostgreSQL only does that inside a database transaction, so the caller must open one.
     */
    public void forEachSince(Instant from, Consumer<Transaction> action) {
        jdbc.sql("""
                        SELECT * FROM transactions
                        WHERE occurred_at >= :from
                        ORDER BY card_id, processed_at, transaction_id
                        """)
                .withFetchSize(1_000)
                .param("from", utc(from))
                .query((RowCallbackHandler) row -> action.accept(toTransaction(row))); // called once per row
    }

    private static Transaction toTransaction(ResultSet row) throws SQLException {
        return new Transaction(Transaction.SCHEMA_VERSION, row.getObject("transaction_id", UUID.class),
                row.getString("source"), row.getString("user_id"), row.getString("card_id"),
                row.getString("merchant"), Category.valueOf(row.getString("category")), row.getLong("amount_cents"),
                row.getString("currency"), row.getString("country"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("published_at", OffsetDateTime.class).toInstant());
    }

    /** The PostgreSQL driver maps OffsetDateTime (not Instant) onto a TIMESTAMPTZ column. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
