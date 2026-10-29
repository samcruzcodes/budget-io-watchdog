package io.budget.watchdog.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.IntegrationTestContainers;
import io.budget.watchdog.schema.IssueType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The precision and recall queries against real Postgres, on hand-made rows that cover each way an alert or a
 * scenario can be counted. The metrics cover the whole database, so this test empties the tables first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class DetectionMetricsIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.register(registry);
    }

    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-03-02T09:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DetectionMetrics metrics;

    @BeforeEach
    void emptyTables() {
        jdbc.sql("TRUNCATE alerts, ground_truth_labels, transactions").update();
    }

    @Test
    void countsEachKindOfAlertAndScenarioTheWayThePrototypeDoes() {
        // Pair 1: the duplicate is caught. A true positive, and its scenario counts as caught.
        UUID pair1 = UUID.randomUUID();
        storedWithLabel(IssueType.NONE, pair1, null);
        UUID duplicate1 = storedWithLabel(IssueType.DUPLICATE_CHARGE, pair1, null);
        alert(duplicate1, "duplicate-charge", IssueType.DUPLICATE_CHARGE);

        // Pair 2: an alert on the untouched original only. A false positive, and the scenario is missed.
        UUID pair2 = UUID.randomUUID();
        UUID original2 = storedWithLabel(IssueType.NONE, pair2, null);
        storedWithLabel(IssueType.DUPLICATE_CHARGE, pair2, null);
        alert(original2, "duplicate-charge", IssueType.DUPLICATE_CHARGE);

        // A double coffee, the planted look-alike: a false positive.
        UUID coffee = storedWithLabel(IssueType.NONE, UUID.randomUUID(), "DOUBLE_COFFEE");
        alert(coffee, "duplicate-charge", IssueType.DUPLICATE_CHARGE);

        // A charge posted three times: two true alerts, one scenario.
        UUID triple = UUID.randomUUID();
        storedWithLabel(IssueType.NONE, triple, null);
        alert(storedWithLabel(IssueType.DUPLICATE_CHARGE, triple, null), "duplicate-charge", IssueType.DUPLICATE_CHARGE);
        alert(storedWithLabel(IssueType.DUPLICATE_CHARGE, triple, null), "duplicate-charge", IssueType.DUPLICATE_CHARGE);

        // An alert whose label hasn't arrived: reported, not scored.
        alert(stored(), "duplicate-charge", IssueType.DUPLICATE_CHARGE);

        // A label whose transaction hasn't arrived: not a scenario yet.
        label(UUID.randomUUID(), IssueType.DUPLICATE_CHARGE, UUID.randomUUID(), null);

        // A card-testing attack nobody caught.
        storedWithLabel(IssueType.CARD_TESTING, UUID.randomUUID(), null);

        List<RuleScore> scores = metrics.scores();

        assertThat(scores).extracting(RuleScore::detector).containsExactly(
                "card-testing", "duplicate-charge", "new-country", "price-hike", "spending-spike", "trial-conversion");
        RuleScore duplicates = scores.get(1);
        assertThat(duplicates).isEqualTo(new RuleScore("duplicate-charge", IssueType.DUPLICATE_CHARGE,
                6, 3, 2, 1, 3, 2, 1, 3 / 5.0, 2 / 3.0));
        RuleScore cardTesting = scores.getFirst();
        assertThat(cardTesting).isEqualTo(new RuleScore("card-testing", IssueType.CARD_TESTING,
                0, 0, 0, 0, 1, 0, 1, null, 0.0));
        assertThat(scores.get(2)).isEqualTo(new RuleScore("new-country", IssueType.NEW_COUNTRY,
                0, 0, 0, 0, 0, 0, 0, null, null));
    }

    private UUID stored() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO transactions (transaction_id, source, user_id, card_id, merchant, category,
                                                  amount_cents, currency, country, occurred_at, published_at,
                                                  processed_at)
                        VALUES (:id, 'SIMULATOR', 'user-0001', 'card-0001', 'Blue Bottle Coffee', 'DINING', 545,
                                'USD', 'US', :time, :time, :time)
                        """)
                .param("id", id).param("time", TIME).update();
        return id;
    }

    private UUID storedWithLabel(IssueType issueType, UUID scenarioId, String edgeCase) {
        UUID id = stored();
        label(id, issueType, scenarioId, edgeCase);
        return id;
    }

    private void label(UUID transactionId, IssueType issueType, UUID scenarioId, String edgeCase) {
        jdbc.sql("""
                        INSERT INTO ground_truth_labels (transaction_id, persona, issue_type, scenario_id, edge_case,
                                                         received_at)
                        VALUES (:id, 'STUDENT', :issueType, :scenarioId, :edgeCase, :time)
                        """)
                .param("id", transactionId).param("issueType", issueType.name()).param("scenarioId", scenarioId)
                .param("edgeCase", edgeCase).param("time", TIME).update();
    }

    private void alert(UUID transactionId, String detector, IssueType issueType) {
        jdbc.sql("""
                        INSERT INTO alerts (alert_id, transaction_id, user_id, card_id, detector, issue_type,
                                            explanation, occurred_at, detected_at, published_at)
                        VALUES (:alertId, :id, 'user-0001', 'card-0001', :detector, :issueType, 'test', :time, :time,
                                :time)
                        """)
                .param("alertId", UUID.randomUUID()).param("id", transactionId).param("detector", detector)
                .param("issueType", issueType.name()).param("time", TIME).update();
    }
}
