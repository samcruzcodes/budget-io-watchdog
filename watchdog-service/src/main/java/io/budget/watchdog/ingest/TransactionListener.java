package io.budget.watchdog.ingest;

import io.budget.watchdog.schema.Topics;
import io.budget.watchdog.schema.Transaction;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the {@code transactions} topic. Spring runs 3 consumer threads in the group "watchdog"; Kafka gives each
 * one partition, so all of a card's transactions are handled in order by one thread. Spring calls this method once
 * per message and commits the offset after it returns, so a crash means redelivery, never loss (at-least-once).
 */
@Component
public class TransactionListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionListener.class);

    private final TransactionParser parser;
    private final TransactionRepository repository;
    private final Clock clock;
    private final AtomicLong stored = new AtomicLong();

    public TransactionListener(TransactionParser parser, TransactionRepository repository, Clock clock) {
        this.parser = parser;
        this.repository = repository;
        this.clock = clock;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "watchdog", concurrency = "3")
    public void onTransaction(ConsumerRecord<String, String> record) {
        Transaction tx = parser.parse(record.value()); // invalid -> InvalidTransactionException -> dead-letter topic
        boolean isNew = repository.insertIfAbsent(tx, clock.instant());
        if (!isNew) {
            // A duplicate *message* (Kafka redelivered it), not a duplicate *charge*: skip it.
            log.info("Skipped transaction {}: already stored", tx.transactionId());
            return;
        }
        // Phase 2: run the detection rules here.
        long total = stored.incrementAndGet();
        if (total == 1 || total % 1_000 == 0) {
            log.info("Stored {} transactions", total);
        }
    }
}
