package io.budget.watchdog.ingest;

import io.budget.watchdog.alerts.AlertPublisher;
import io.budget.watchdog.alerts.AlertRepository;
import io.budget.watchdog.rules.RuleEngine;
import io.budget.watchdog.rules.RuleHit;
import io.budget.watchdog.schema.Alert;
import io.budget.watchdog.schema.Topics;
import io.budget.watchdog.schema.Transaction;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes the {@code transactions} topic. Spring runs 3 consumer threads in the group "watchdog"; Kafka gives each
 * one partition, so all of a card's transactions are handled in order by one thread. Spring calls this method once
 * per message and commits the offset after it returns, so a crash means redelivery, never loss (at-least-once).
 */
@Component
public class TransactionListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionListener.class);

    private final TransactionParser parser;
    private final TransactionRepository transactions;
    private final AlertRepository alerts;
    private final AlertPublisher publisher;
    private final RuleEngine ruleEngine;
    private final TransactionTemplate database;
    private final Clock clock;
    private final AtomicLong stored = new AtomicLong();

    public TransactionListener(TransactionParser parser, TransactionRepository transactions, AlertRepository alerts,
            AlertPublisher publisher, RuleEngine ruleEngine, TransactionTemplate database, Clock clock) {
        this.parser = parser;
        this.transactions = transactions;
        this.alerts = alerts;
        this.publisher = publisher;
        this.ruleEngine = ruleEngine;
        this.database = database;
        this.clock = clock;
    }

    /** What storing one message did: whether the transaction was new, and the alerts still to publish. */
    private record Stored(boolean isNew, List<Alert> toPublish) {}

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "watchdog", concurrency = "3")
    public void onTransaction(ConsumerRecord<String, String> record) {
        Transaction tx = parser.parse(record.value()); // invalid -> InvalidTransactionException -> dead-letter topic

        // The transaction row and its alerts commit together. If saving an alert fails, both roll back, and Kafka's
        // retry sees the transaction as new again (the rule engine hands back the same hits).
        Stored result = database.execute(status -> store(tx));

        if (result.isNew()) {
            for (Alert alert : result.toPublish()) {
                log.info("Rule hit {} on {} at {} (transaction {}): {}", alert.detector(), tx.cardId(),
                        tx.occurredAt(), tx.transactionId(), alert.explanation());
            }
        } else {
            // A duplicate *message* (Kafka redelivered it), not a duplicate *charge*: the rules never see it.
            log.info("Skipped transaction {}: already stored", tx.transactionId());
            if (!result.toPublish().isEmpty()) {
                log.info("Republishing {} alerts for transaction {}", result.toPublish().size(), tx.transactionId());
            }
        }

        // Saving and publishing can't be one atomic step. An alert stays unpublished in Postgres until Kafka confirms
        // it, so if publishing fails here, or the app dies first, the redelivered message publishes it again.
        for (Alert alert : result.toPublish()) {
            publisher.publish(alert);
            alerts.markPublished(alert.alertId(), clock.instant());
        }

        if (result.isNew()) {
            long total = stored.incrementAndGet();
            if (total == 1 || total % 1_000 == 0) {
                log.info("Stored {} transactions", total);
            }
        }
    }

    private Stored store(Transaction tx) {
        if (!transactions.insertIfAbsent(tx, clock.instant())) {
            return new Stored(false, alerts.unpublishedFor(tx.transactionId()));
        }
        // Only new transactions reach the rules, so a redelivered message can't look like a duplicate charge.
        Instant detectedAt = clock.instant();
        List<Alert> newAlerts = new ArrayList<>();
        for (RuleHit hit : ruleEngine.evaluate(tx)) {
            newAlerts.add(Alert.of(tx, hit.ruleId(), hit.issueType(), hit.explanation(), detectedAt));
        }
        alerts.insertAll(newAlerts);
        return new Stored(true, newAlerts);
    }
}
