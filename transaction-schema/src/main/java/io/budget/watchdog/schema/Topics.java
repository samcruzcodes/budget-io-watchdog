package io.budget.watchdog.schema;

/** Kafka topic names. They are part of the contract between sources and the watchdog. */
public final class Topics {

    /** Transaction v1 messages, keyed by cardId so each card's transactions stay in order. */
    public static final String TRANSACTIONS = "transactions";

    /** Ground-truth labels from the simulator, keyed by transactionId. Read only by evaluation code. */
    public static final String TRANSACTION_LABELS = "transaction-labels";

    /**
     * Messages the watchdog can never process, with the error in their headers. Needs at least as many
     * partitions as {@link #TRANSACTIONS}, because a failed message keeps its partition number.
     */
    public static final String TRANSACTIONS_DLT = "transactions.DLT";

    /** Labels the watchdog can never process. Same partition count as {@link #TRANSACTION_LABELS}, for the same reason. */
    public static final String TRANSACTION_LABELS_DLT = "transaction-labels.DLT";

    /** Alert v1 messages, keyed by cardId. At-least-once: readers should drop duplicates by alertId. */
    public static final String ALERTS = "alerts";

    private Topics() {
    }
}
