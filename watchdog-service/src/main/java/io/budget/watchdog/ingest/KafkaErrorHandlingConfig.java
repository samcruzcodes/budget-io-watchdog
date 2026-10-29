package io.budget.watchdog.ingest;

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
 *   <li>{@link InvalidMessageException}: the message itself is bad, and retrying can't fix it. It goes to its own
 *       topic's dead-letter topic ({@code <topic>.DLT}) at once, with the error in its headers, so it can't block its
 *       partition.</li>
 *   <li>Anything else (the database or Kafka is down, a network blip): retry with growing waits, without giving up.
 *       Skipping would lose a valid message and break per-card ordering, so the partition waits until the problem
 *       clears.</li>
 * </ul>
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        // A failed message keeps its partition number in the DLT; that's why each DLT has as many partitions as its topic.
        var deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic(record.topic()), record.partition()));
        // Log every message we give up on, so bad data can't pile up unnoticed.
        ConsumerRecordRecoverer logThenDeadLetter = (record, exception) -> {
            Throwable reason = exception.getCause() != null ? exception.getCause() : exception;
            log.warn("Sent {}-{} offset {} to {}: {}", record.topic(), record.partition(), record.offset(),
                    deadLetterTopic(record.topic()), reason.getMessage());
            deadLetters.accept(record, exception);
        };

        var backOff = new ExponentialBackOff(1_000, 2.0); // wait 1 s, 2 s, 4 s, ...
        backOff.setMaxInterval(30_000);                    // ... at most 30 s between tries, with no limit on tries

        var errorHandler = new DefaultErrorHandler(logThenDeadLetter, backOff);
        errorHandler.addNotRetryableExceptions(InvalidMessageException.class);
        return errorHandler;
    }

    /** "transactions" -> "transactions.DLT". Every dead-letter topic is listed in kafka-init and in Topics. */
    static String deadLetterTopic(String topic) {
        return topic + ".DLT";
    }
}
