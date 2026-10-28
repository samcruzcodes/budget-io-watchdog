package io.budget.watchdog.ingest;

import io.budget.watchdog.schema.Topics;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens when the listener throws. Spring Boot attaches this error handler to every Kafka listener.
 *
 * <ul>
 *   <li>{@link InvalidTransactionException}: the message itself is bad, and retrying can't fix it. It goes to the
 *       dead-letter topic at once, with the error in its headers, so it can't block its partition.</li>
 *   <li>Anything else (the database is down, a network blip): retry with growing waits, without giving up. Skipping
 *       would lose a valid transaction and break per-card ordering, so the partition waits until the problem
 *       clears.</li>
 * </ul>
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        // A failed message keeps its partition number in the DLT; that's why the DLT has as many partitions as the topic.
        var deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(Topics.TRANSACTIONS_DLT, record.partition()));
        // Log every message we give up on, so bad data can't pile up unnoticed.
        ConsumerRecordRecoverer logThenDeadLetter = (record, exception) -> {
            Throwable reason = exception.getCause() != null ? exception.getCause() : exception;
            log.warn("Sent {}-{} offset {} to {}: {}", record.topic(), record.partition(), record.offset(),
                    Topics.TRANSACTIONS_DLT, reason.getMessage());
            deadLetters.accept(record, exception);
        };

        var backOff = new ExponentialBackOff(1_000, 2.0); // wait 1 s, 2 s, 4 s, ...
        backOff.setMaxInterval(30_000);                    // ... at most 30 s between tries, with no limit on tries

        var errorHandler = new DefaultErrorHandler(logThenDeadLetter, backOff);
        errorHandler.addNotRetryableExceptions(InvalidTransactionException.class);
        return errorHandler;
    }
}
