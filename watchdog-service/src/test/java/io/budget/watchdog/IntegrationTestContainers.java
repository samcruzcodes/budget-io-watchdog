package io.budget.watchdog;

import io.budget.watchdog.schema.Topics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One real Kafka and one real Postgres for every integration test in the run, the same images as docker-compose.yml.
 * They start once, the first time a test touches this class, and Testcontainers removes them when the JVM exits.
 * Tests share the data, so each test uses its own card IDs. Spring isn't put in charge of them on purpose: it would
 * stop them when a test's context closes, and the next test class would find them gone.
 */
public final class IntegrationTestContainers {

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        KAFKA.start();
        POSTGRES.start();
        createTopics();
    }

    private IntegrationTestContainers() {
    }

    /** Like kafka-init: 3 partitions each, so the dead-letter topic matches and nothing is created by accident. */
    private static void createTopics() {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            List<NewTopic> topics = List.of(Topics.TRANSACTIONS, Topics.TRANSACTIONS_DLT, Topics.ALERTS).stream()
                    .map(name -> new NewTopic(name, 3, (short) 1))
                    .toList();
            admin.createTopics(topics).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("could not create the test topics", e);
        }
    }

    /** For {@code @DynamicPropertySource}: points a {@code @SpringBootTest}'s Kafka and datasource at the containers. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** The same settings as command-line arguments, for starting the app by hand (WarmUpIT restarts it). */
    public static String[] connectionProperties() {
        return new String[] {
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
        };
    }
}
