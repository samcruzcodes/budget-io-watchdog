package io.budget.watchdog.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.budget.watchdog.IntegrationTestContainers;
import io.budget.watchdog.TestKafka;
import io.budget.watchdog.schema.EdgeCase;
import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.MessageJson;
import io.budget.watchdog.schema.Topics;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Labels from Kafka into Postgres, and bad messages to the dead-letter topic of the topic they came from. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class LabelPipelineIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.register(registry);
    }

    @Autowired
    JdbcClient jdbc;

    @Test
    void aLabelSentTwiceIsStoredOnce() {
        GroundTruthLabel label = new GroundTruthLabel(1, UUID.randomUUID(), "STUDENT", IssueType.NONE,
                UUID.randomUUID(), EdgeCase.DOUBLE_COFFEE);
        GroundTruthLabel marker = new GroundTruthLabel(1, UUID.randomUUID(), "STUDENT", IssueType.NONE, null, null);
        String key = label.transactionId().toString();

        // The same label twice, then a marker with the same key, so it lands on the same partition and is handled
        // after both copies.
        TestKafka.sendRaw(Topics.TRANSACTION_LABELS, key, MessageJson.write(label));
        TestKafka.sendRaw(Topics.TRANSACTION_LABELS, key, MessageJson.write(label));
        TestKafka.sendRaw(Topics.TRANSACTION_LABELS, key, MessageJson.write(marker));

        await().atMost(Duration.ofSeconds(30)).until(() -> labelRows(marker.transactionId()) == 1);
        assertThat(labelRows(label.transactionId())).isEqualTo(1);
        Map<String, Object> row = jdbc.sql("SELECT * FROM ground_truth_labels WHERE transaction_id = :id")
                .param("id", label.transactionId()).query().singleRow();
        assertThat(row)
                .containsEntry("persona", "STUDENT")
                .containsEntry("issue_type", "NONE")
                .containsEntry("scenario_id", label.scenarioId())
                .containsEntry("edge_case", "DOUBLE_COFFEE");
    }

    @Test
    void aBadLabelGoesToTheLabelsDeadLetterTopic() {
        String bad = "{\"marker\": \"" + UUID.randomUUID() + "\"}";

        TestKafka.sendRaw(Topics.TRANSACTION_LABELS, "bad-label", bad);

        await().atMost(Duration.ofSeconds(30)).until(() -> TestKafka.messagesOn(Topics.TRANSACTION_LABELS_DLT)
                .stream().anyMatch(record -> bad.equals(record.value())));
    }

    @Test
    void aBadTransactionStillGoesToTheTransactionsDeadLetterTopic() {
        String bad = "{\"marker\": \"" + UUID.randomUUID() + "\"}";

        TestKafka.sendRaw(Topics.TRANSACTIONS, "card-bad", bad);

        await().atMost(Duration.ofSeconds(30)).until(() -> TestKafka.messagesOn(Topics.TRANSACTIONS_DLT)
                .stream().anyMatch(record -> bad.equals(record.value())));
    }

    private long labelRows(UUID transactionId) {
        return jdbc.sql("SELECT count(*) FROM ground_truth_labels WHERE transaction_id = :id")
                .param("id", transactionId).query(Long.class).single();
    }
}
