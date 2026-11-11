package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.MessageJson;
import io.budget.watchdog.schema.Topics;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds where an earlier simulator process stopped, so a restarted one continues instead of starting over.
 *
 * <p>The simulation is deterministic: the same seed always plans the same transactions with the same IDs. Without
 * this, a restarted simulator would publish everything again from the first day, and the watchdog, which stores each
 * ID once, would spend minutes throwing those messages away while the dashboard stood still.
 *
 * <p>The simulator keeps no file or database, so its own topic is its memory. This reads the newest message on each
 * partition of {@code transactions} and takes the earliest of those times. Earliest, because a crash can leave one
 * partition a little behind the others, and starting slightly too early is safe: the watchdog drops the few messages
 * it has already stored. Starting too late would lose transactions.
 */
final class ResumePoint {

    private static final Logger log = LoggerFactory.getLogger(ResumePoint.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_POLLS = 30; // one second each

    private ResumePoint() {
    }

    /**
     * @return the simulated moment to continue from, or empty if the topic holds nothing to continue from (a new
     *         run, or Kafka has already deleted the old messages)
     */
    static Optional<Instant> find(String bootstrapServers) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // No consumer group: this only looks at the end of the topic and never commits a position.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(Topics.TRANSACTIONS, TIMEOUT).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> first = consumer.beginningOffsets(partitions, TIMEOUT);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, TIMEOUT);
            // An offset is a message's position in its partition. "end" is where the next message will go, so the
            // newest message is at end - 1. A partition whose first and end offsets are equal holds nothing.
            List<TopicPartition> withMessages = partitions.stream()
                    .filter(partition -> end.get(partition) > first.get(partition))
                    .toList();
            if (withMessages.isEmpty()) {
                return Optional.empty();
            }
            // Read each partition from its newest message; an empty one from its end, where there's nothing to read.
            partitions.forEach(partition -> consumer.seek(partition,
                    withMessages.contains(partition) ? end.get(partition) - 1 : end.get(partition)));

            Map<TopicPartition, Optional<Instant>> newest = new HashMap<>();
            for (int poll = 0; poll < MAX_POLLS && newest.size() < withMessages.size(); poll++) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    newest.putIfAbsent(new TopicPartition(record.topic(), record.partition()), occurredAt(record));
                }
            }
            if (newest.size() < withMessages.size()) {
                // Guessing here could skip transactions that were never published. Failing makes Docker or
                // Kubernetes restart the simulator, which tries again.
                throw new IllegalStateException("couldn't read the newest message of every partition of "
                        + Topics.TRANSACTIONS + " within " + MAX_POLLS + " s");
            }
            return newest.values().stream().flatMap(Optional::stream).min(Comparator.naturalOrder());
        }
    }

    /** The message's simulated time, or empty if it isn't a valid transaction (someone published junk by hand). */
    private static Optional<Instant> occurredAt(ConsumerRecord<String, String> record) {
        try {
            return Optional.of(MessageJson.read(record.value(), Transaction.class).occurredAt());
        } catch (RuntimeException e) {
            log.warn("Ignoring {}-{} when looking for where to continue: its newest message isn't a valid transaction",
                    record.topic(), record.partition());
            return Optional.empty();
        }
    }
}
