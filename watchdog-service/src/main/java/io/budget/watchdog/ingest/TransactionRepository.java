package io.budget.watchdog.ingest;

import io.budget.watchdog.schema.Transaction;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /** The PostgreSQL driver maps OffsetDateTime (not Instant) onto a TIMESTAMPTZ column. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
