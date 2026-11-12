package io.budget.watchdog.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.budget.watchdog.IntegrationTestContainers;
import io.budget.watchdog.TestKafka;
import io.budget.watchdog.schema.Alert;
import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.MessageJson;
import io.budget.watchdog.schema.Topics;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A demo scenario the way the simulator sends one: transactions on {@code transactions}, their labels on
 * {@code transaction-labels}, tied together by a scenario ID. The API must find them again by that ID, with the alert
 * the real rules raised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class ScenariosApiIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.register(registry);
    }

    private static final Instant NOW = Instant.parse("2027-03-01T09:00:00Z");

    @LocalServerPort
    int port;

    @Test
    void aScenarioIsFoundByItsIdWithTheAlertTheRulesRaised() {
        UUID scenarioId = UUID.randomUUID();
        String card = "demo-" + scenarioId.toString().substring(0, 8);
        Transaction original = demoCharge(card, NOW.minusSeconds(24));
        Transaction duplicate = demoCharge(card, NOW);
        // Something else on another card at the same moment, labeled with no scenario: it must not show up.
        Transaction unrelated = TestKafka.charge(TestKafka.newCard(), "Whole Foods Market", "US", 6_427, NOW);

        TestKafka.send(original, duplicate, unrelated);
        sendLabel(original, IssueType.NONE, scenarioId);
        sendLabel(duplicate, IssueType.DUPLICATE_CHARGE, scenarioId);
        sendLabel(unrelated, IssueType.NONE, null);

        // Transactions and labels arrive on separate topics, so the result fills in over a moment.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                given().port(port)
                        .when().get("/api/scenarios/{id}", scenarioId)
                        .then()
                        .statusCode(200)
                        .body("scenarioId", is(scenarioId.toString()))
                        .body("transactions.transactionId",
                                contains(original.transactionId().toString(), duplicate.transactionId().toString()))
                        .body("transactions.source", contains("DEMO", "DEMO"))
                        .body("alerts", hasSize(1))
                        .body("alerts[0].alertId",
                                is(Alert.idFor(duplicate.transactionId(), "duplicate-charge").toString()))
                        .body("alerts[0].transactionId", is(duplicate.transactionId().toString()))
                        .body("alerts[0].detector", is("duplicate-charge"))
                        .body("alerts[0].explanation", is("Whole Foods Market charged $64.27 twice, 24 seconds apart.")));
    }

    @Test
    void aScenarioNothingIsStoredForIsEmptyNotAnError() {
        given().port(port)
                .when().get("/api/scenarios/{id}", UUID.randomUUID())
                .then()
                .statusCode(200)
                .body("transactions", empty())
                .body("alerts", empty());
    }

    @Test
    void anIdThatIsNotAUuidIsA400() {
        given().port(port)
                .when().get("/api/scenarios/{id}", "duplicate-charge")
                .then()
                .statusCode(400);
    }

    private static Transaction demoCharge(String card, Instant occurredAt) {
        return new Transaction(Transaction.SCHEMA_VERSION, UUID.randomUUID(), Transaction.DEMO_SOURCE, "demo-user",
                card, "Whole Foods Market", Category.GROCERIES, 6_427, "USD", "US", occurredAt, Instant.now());
    }

    private static void sendLabel(Transaction transaction, IssueType issueType, UUID scenarioId) {
        GroundTruthLabel label = new GroundTruthLabel(GroundTruthLabel.SCHEMA_VERSION, transaction.transactionId(),
                "DEMO", issueType, scenarioId, null);
        TestKafka.sendRaw(Topics.TRANSACTION_LABELS, transaction.transactionId().toString(), MessageJson.write(label));
    }
}
