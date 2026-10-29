package io.budget.watchdog.ingest;

import io.budget.watchdog.rules.RuleEngine;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rebuilds the rules' memory at startup, then starts the Kafka listener. The rules keep each card's history in memory,
 * so a restarted watchdog would otherwise start blind. It replays the last {@code lookback} of stored transactions
 * through the rules and throws the hits away, since their alerts were raised the first time.
 *
 * <p>The window is measured back from the newest stored {@code occurred_at}, never from the wall clock: simulated time
 * runs years ahead of real time. Spring Boot only reports the app as ready once runners like this one have finished,
 * so readiness stays false during the warm-up. The listener must wait too, because Kafka hands partitions to any
 * consumer that joins, ready or not.
 */
@Component
class WarmUp implements ApplicationRunner {

    @ConfigurationProperties("watchdog.warm-up")
    record Settings(Duration lookback) {

        Settings {
            if (lookback == null || !lookback.isPositive()) {
                throw new IllegalArgumentException("watchdog.warm-up.lookback must be positive, got " + lookback);
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(WarmUp.class);

    private final Settings settings;
    private final TransactionRepository transactions;
    private final RuleEngine ruleEngine;
    private final KafkaListenerEndpointRegistry listeners;
    private final TransactionTemplate readOnly;

    WarmUp(Settings settings, TransactionRepository transactions, RuleEngine ruleEngine,
            KafkaListenerEndpointRegistry listeners, PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.transactions = transactions;
        this.ruleEngine = ruleEngine;
        this.listeners = listeners;
        // Streaming rows from PostgreSQL needs a database transaction; read-only, since nothing is written.
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<Instant> newest = transactions.newestOccurredAt();
        if (newest.isEmpty()) {
            log.info("Warm-up: nothing stored yet");
        } else {
            replaySince(newest.get().minus(settings.lookback()), newest.get());
        }
        listeners.getListenerContainer(TransactionListener.ID).start();
        log.info("Started the transactions listener");
    }

    private void replaySince(Instant from, Instant newest) {
        long started = System.nanoTime();
        AtomicLong replayed = new AtomicLong();
        Set<String> cards = new HashSet<>();
        readOnly.executeWithoutResult(status -> transactions.forEachSince(from, tx -> {
            ruleEngine.evaluate(tx); // the hits were already raised as alerts the first time
            replayed.incrementAndGet();
            cards.add(tx.cardId());
        }));
        log.info("Warm-up finished: replayed {} transactions on {} cards, from {} to {}, in {} ms", replayed.get(),
                cards.size(), from, newest, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
