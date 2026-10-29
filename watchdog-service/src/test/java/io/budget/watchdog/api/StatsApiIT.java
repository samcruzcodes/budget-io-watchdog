package io.budget.watchdog.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;

import io.budget.watchdog.IntegrationTestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@code GET /api/stats} over real HTTP: the JSON shape the dashboard will rely on. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class StatsApiIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.register(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void returnsAScorePerRuleWithThroughputAndLatency() {
        given().port(port)
                .when().get("/api/stats")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("detectors.detector", contains("card-testing", "duplicate-charge", "new-country", "price-hike",
                        "spending-spike", "trial-conversion"))
                .body("detectors.issueType", contains("CARD_TESTING", "DUPLICATE_CHARGE", "NEW_COUNTRY", "PRICE_HIKE",
                        "SPENDING_SPIKE", "TRIAL_CONVERSION"))
                // Every field is present, even when it's null ("no data yet"), so the dashboard can show "n/a".
                .body("detectors", everyItem(hasKey("precision")))
                .body("detectors", everyItem(hasKey("recall")))
                .body("detectors", everyItem(hasKey("falseNegatives")))
                .body("detectors", everyItem(hasKey("unlabeledAlerts")))
                .body("throughput.windowSeconds", is(60))
                .body("latency.windowSeconds", is(300))
                .body("latency", hasKey("p99Ms"));
    }
}
