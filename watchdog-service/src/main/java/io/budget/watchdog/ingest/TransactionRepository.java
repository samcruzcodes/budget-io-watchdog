package io.budget.watchdog.ingest;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.Transaction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores and reads transactions with plain SQL through Spring's JdbcClient. */
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

    /** The newest transactions, by event time, for one user or (with a null userId) everyone. */
    public List<Transaction> recent(String userId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM transactions
                        WHERE CAST(:userId AS text) IS NULL OR user_id = :userId
                        ORDER BY occurred_at DESC, processed_at DESC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("limit", limit)
                .query((row, n) -> toTransaction(row))
                .list();
    }

    /**
     * One user's transactions since {@code from}, each card and merchant together, in the order they happened (event
     * time). The rules follow arrival order because they stream; this reads history, so a charge that arrives late
     * still lands where it belongs. Ties fall back to arrival order.
     */
    public List<Transaction> forUserSince(String userId, Instant from) {
        return jdbc.sql("""
                        SELECT * FROM transactions
                        WHERE user_id = :userId AND occurred_at >= :from
                        ORDER BY card_id, merchant, occurred_at, processed_at, transaction_id
                        """)
                .param("userId", userId)
                .param("from", utc(from))
                .query((row, n) -> toTransaction(row))
                .list();
    }

    /** The event time of one user's newest transaction: "now" for that user, since simulated time runs ahead. */
    public Optional<Instant> newestOccurredAtFor(String userId) {
        return jdbc.sql("SELECT occurred_at FROM transactions WHERE user_id = :userId ORDER BY occurred_at DESC LIMIT 1")
                .param("userId", userId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /** One user's totals per category for transactions that occurred in [start, end). */
    public Map<Category, Long> totalsByCategory(String userId, Instant start, Instant end) {
        Map<Category, Long> totals = new EnumMap<>(Category.class);
        jdbc.sql("""
                        SELECT category, sum(amount_cents) AS cents FROM transactions
                        WHERE user_id = :userId AND occurred_at >= :start AND occurred_at < :end
                        GROUP BY category
                        """)
                .param("userId", userId)
                .param("start", utc(start))
                .param("end", utc(end))
                .query((RowCallbackHandler) row -> totals.put(Category.valueOf(row.getString("category")),
                        row.getLong("cents")));
        return totals;
    }

    /** The stored transactions among {@code ids}, in the order they happened (ties in arrival order). */
    public List<Transaction> byIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of(); // "IN ()" isn't valid SQL
        }
        return jdbc.sql("""
                        SELECT * FROM transactions
                        WHERE transaction_id IN (:ids)
                        ORDER BY occurred_at, processed_at, transaction_id
                        """)
                .param("ids", ids)
                .query((row, n) -> toTransaction(row))
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM transactions").query(Long.class).single();
    }

    /** One user: their cards, how many transactions they have, and the event time of the newest. */
    public record UserSummary(String userId, List<String> cardIds, long transactions, Instant newestOccurredAt) {}

    /** Every user with at least one stored transaction, by user ID. */
    public List<UserSummary> users() {
        return jdbc.sql("""
                        SELECT user_id, array_agg(DISTINCT card_id ORDER BY card_id) AS card_ids,
                               count(*) AS transactions, max(occurred_at) AS newest
                        FROM transactions
                        GROUP BY user_id
                        ORDER BY user_id
                        """)
                .query((row, n) -> new UserSummary(row.getString("user_id"),
                        List.of((String[]) row.getArray("card_ids").getArray()), row.getLong("transactions"),
                        row.getObject("newest", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Transactions and spending (everything except income) on one UTC day. */
    public record DayTotals(LocalDate day, long transactions, long spendingCents) {}

    /** Per UTC day of event time, for transactions that occurred in [start, end); days with none are left out. */
    public List<DayTotals> dailyTotals(Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, count(*) AS transactions,
                               coalesce(sum(amount_cents) FILTER (WHERE category <> 'INCOME'), 0) AS spending
                        FROM transactions
                        WHERE occurred_at >= :start AND occurred_at < :end
                        GROUP BY 1
                        ORDER BY 1
                        """)
                .param("start", utc(start))
                .param("end", utc(end))
                .query((row, n) -> new DayTotals(row.getObject("day", LocalDate.class), row.getLong("transactions"),
                        row.getLong("spending")))
                .list();
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
