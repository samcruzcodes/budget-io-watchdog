package io.budget.watchdog;

import io.budget.watchdog.schema.Alert;
import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.MessageJson;
import io.budget.watchdog.schema.Topics;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Sends test transactions to the test Kafka and reads back what the watchdog published, with the plain client API. */
public final class TestKafka {

    private TestKafka() {
    }

    /** A card charge with a fresh transaction ID; every other field is fixed. */
    public static Transaction charge(String cardId, String merchant, String country, long cents, Instant occurredAt) {
        return new Transaction(Transaction.SCHEMA_VERSION, UUID.randomUUID(), "SIMULATOR",
                cardId.replace("card", "user"), cardId, merchant, Category.SHOPPING, cents, "USD", country,
                occurredAt, Instant.now());
    }

    /** A new card ID per test, so tests sharing one database can't see each other's data. */
    public static String newCard() {
        return "card-" + UUID.randomUUID();
    }

    /** Publishes to {@code transactions}, keyed by card like the simulator, and waits until Kafka has it. */
    public static void send(Transaction... transactions) {
        for (Transaction tx : transactions) {
            sendRaw(Topics.TRANSACTIONS, tx.cardId(), MessageJson.write(tx));
        }
    }

    /** Publishes any value, valid or not, and waits until Kafka has it. */
    public static void sendRaw(String topic, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, IntegrationTestContainers.KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("could not send a test message", e);
        }
    }

    /** One message on the {@code alerts} topic: its key and its parsed value. */
    public record PublishedAlert(String key, Alert alert) {}

    /** Every message on {@code alerts} for one card, read from the beginning of the topic. */
    public static List<PublishedAlert> alertsPublishedFor(String cardId) {
        return messagesOn(Topics.ALERTS).stream()
                .map(record -> new PublishedAlert(record.key(), MessageJson.read(record.value(), Alert.class)))
                .filter(published -> published.alert().cardId().equals(cardId))
                .toList();
    }

    /** Every message on a topic so far, read from the beginning without joining a consumer group. */
    public static List<ConsumerRecord<String, String>> messagesOn(String topic) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, IntegrationTestContainers.KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1),
                    new TopicPartition(topic, 2));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    found.add(record);
                }
            }
        }
        return found;
    }
}
