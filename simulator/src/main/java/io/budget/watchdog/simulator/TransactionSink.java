package io.budget.watchdog.simulator;

/** Where planned transactions go: Kafka in production, a list in tests. */
interface TransactionSink extends AutoCloseable {

    /** Sends the transaction and its ground-truth label. */
    void publish(PlannedTransaction planned);

    @Override
    void close();
}
