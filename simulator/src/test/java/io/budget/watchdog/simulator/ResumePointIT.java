package io.budget.watchdog.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.MessageJson;
import io.budget.watchdog.schema.Topics;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A simulator restart against a real Kafka, the same image as docker-compose.yml: the second process must continue
 * where the first stopped. Before {@link ResumePoint} it published the whole run again with the same IDs, and the
 * watchdog skipped every message, so the dashboard showed a running simulator with nothing arriving.
 */
class ResumePointIT {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");
    private static final SimulatorConfig CONFIG = SimulationTest.config(42, 3, SimulatorConfig.Rates.DEFAULTS);
    private static final int FIRST_RUN_DAYS = 20;
    private static final int TOTAL_DAYS = 30;

    @BeforeAll
    static void startKafka() throws Exception {
        KAFKA.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            // 3 partitions, like kafka-init, so the three cards can land on different ones.
            admin.createTopics(List.of(new NewTopic(Topics.TRANSACTIONS, 3, (short) 1),
                    new NewTopic(Topics.TRANSACTION_LABELS, 3, (short) 1))).all().get();
        }
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @Test
    void aRestartedSimulatorContinuesInsteadOfPublishingTheRunAgain() throws Exception {
        String kafka = KAFKA.getBootstrapServers();
        List<UUID> uninterrupted = SimulationTest.simulate(CONFIG, TOTAL_DAYS).stream()
                .map(PlannedTransaction::transactionId).toList();

        // A new topic: nothing to continue from.
        assertThat(ResumePoint.find(kafka)).isEmpty();

        // The first process publishes 20 days and stops.
        long first = run(kafka, null, FIRST_RUN_DAYS);
        List<Transaction> afterFirst = readTopic(kafka);
        assertThat(afterFirst).hasSize((int) first);

        // The second finds where the first stopped: no later than the newest transaction, and no earlier than the
        // card that has been quiet the longest (each partition's newest message is some card's newest).
        Optional<Instant> resumeFrom = ResumePoint.find(kafka);
        Instant newest = afterFirst.stream().map(Transaction::occurredAt).max(Instant::compareTo).orElseThrow();
        Instant quietestCardsNewest = afterFirst.stream()
                .collect(Collectors.toMap(Transaction::cardId, Transaction::occurredAt, (a, b) -> a.isAfter(b) ? a : b))
                .values().stream().min(Instant::compareTo).orElseThrow();
        assertThat(resumeFrom).isPresent();
        assertThat(resumeFrom.get()).isBetween(quietestCardsNewest, newest);

        // It runs on to day 30. The topic then holds every transaction of an uninterrupted 30-day run...
        run(kafka, resumeFrom.get(), TOTAL_DAYS);
        List<Transaction> afterRestart = readTopic(kafka);
        assertThat(afterRestart).extracting(Transaction::transactionId).containsAll(uninterrupted);
        assertThat(afterRestart.stream().map(Transaction::transactionId).distinct()).hasSameSizeAs(uninterrupted);
        // ...and the only repeats are the few around the moment it continued from, not the first 20 days again.
        long repeats = afterRestart.size() - uninterrupted.size();
        long sentBeforeAndAtOrAfterTheResumePoint = afterFirst.stream()
                .filter(tx -> !tx.occurredAt().isBefore(resumeFrom.get())).count();
        assertThat(repeats).isEqualTo(sentBeforeAndAtOrAfterTheResumePoint).isLessThan(first / 4);

        // Junk published by hand as a partition's newest message doesn't stop the next restart: that partition is
        // ignored and the others still say where to continue.
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(Topics.TRANSACTIONS, 0, "card-0001", "not a transaction")).get();
        }
        assertThat(ResumePoint.find(kafka)).isPresent();
    }

    /** One simulator process: plans from the first day, publishes to Kafka at full speed, and exits. */
    private static long run(String kafka, Instant resumeFrom, int days) throws InterruptedException {
        SimClock clock = new SimClock(SimulatorRunner.firstMoment(SimulationTest.START, resumeFrom),
                Double.POSITIVE_INFINITY, InstantSource.system(), false);
        try (KafkaSink sink = new KafkaSink(kafka, InstantSource.system())) {
            return new SimulatorRunner(Simulation.create(CONFIG), clock, sink, resumeFrom, 0)
                    .run(SimulationTest.START, days);
        } // closing the sink sends whatever is still queued
    }

    /** Every message on {@code transactions}, from the beginning. */
    private static List<Transaction> readTopic(String kafka) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()))) {
            List<TopicPartition> partitions = consumer.partitionsFor(Topics.TRANSACTIONS).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long expected = end.values().stream().mapToLong(Long::longValue).sum();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<Transaction> transactions = new ArrayList<>();
            for (int poll = 0; poll < 30 && transactions.size() < expected; poll++) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    transactions.add(MessageJson.read(record.value(), Transaction.class));
                }
            }
            assertThat(transactions).hasSize((int) expected);
            return transactions;
        }
    }
}
