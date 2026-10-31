package io.budget.watchdog.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.budget.watchdog.IntegrationTestContainers;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code /api/users} and {@code /api/activity}, the two endpoints the dashboard needed. The rows are in the year 2100,
 * so they're the newest in the shared test database and set "today" for the activity window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsersAndActivityApiIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.register(registry);
    }

    private final String user = "user-" + UUID.randomUUID();
    private final String card = user.replace("user", "card");

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int port;

    @BeforeAll
    void threeDaysWithAQuietOneInTheMiddle() {
        stored("Northwind Payroll", "INCOME", 420_000, "2100-03-01T06:00:00Z");
        stored("Corner Market", "GROCERIES", 3_125, "2100-03-01T18:00:00Z");
        // March 2: nothing
        UUID coffee = stored("Blue Bottle Coffee", "DINING", 545, "2100-03-03T09:00:00Z");
        alert(coffee, "2100-03-03T09:00:00Z");
    }

    @Test
    void usersListsEachUserWithTheirCardsAndCounts() {
        given().port(port)
                .when().get("/api/users")
                .then().statusCode(200)
                .body("userId", hasItem(user))
                .body("find { it.userId == '" + user + "' }.cardIds", contains(card))
                .body("find { it.userId == '" + user + "' }.transactions", is(3))
                .body("find { it.userId == '" + user + "' }.newestOccurredAt", is("2100-03-03T09:00:00Z"));
    }

    @Test
    void activityListsEveryDayUpToTheNewestTransactionZerosIncluded() {
        given().port(port).queryParam("days", 3)
                .when().get("/api/activity")
                .then().statusCode(200)
                .body("from", is("2100-03-01"))
                .body("to", is("2100-03-03"))
                .body("days.date", contains("2100-03-01", "2100-03-02", "2100-03-03"))
                .body("days.transactions", contains(2, 0, 1))
                .body("days.spendingCents", contains(3_125, 0, 545)) // income isn't spending
                .body("days.alerts", contains(0, 0, 1));
    }

    @Test
    void oneDayIsJustTheNewestDay() {
        given().port(port).queryParam("days", 1)
                .when().get("/api/activity")
                .then().statusCode(200)
                .body("days", hasSize(1))
                .body("days[0].date", is("2100-03-03"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            /api/activity?days=0    | days must be between 1 and 365
            /api/activity?days=366  | days must be between 1 and 365
            /api/activity?days=abc  | days
            """)
    void badDaysGetA400(String path, String reason) {
        given().port(port).urlEncodingEnabled(false)
                .when().get(path)
                .then().statusCode(400)
                .contentType("application/problem+json")
                .body("detail", containsString(reason));
    }

    private UUID stored(String merchant, String category, long cents, String occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO transactions (transaction_id, source, user_id, card_id, merchant, category,
                                                  amount_cents, currency, country, occurred_at, published_at,
                                                  processed_at)
                        VALUES (:id, 'SIMULATOR', :user, :card, :merchant, :category, :cents, 'USD', 'US', :time,
                                now(), now())
                        """)
                .param("id", id).param("user", user).param("card", card).param("merchant", merchant)
                .param("category", category).param("cents", cents).param("time", OffsetDateTime.parse(occurredAt))
                .update();
        return id;
    }

    private void alert(UUID transactionId, String occurredAt) {
        jdbc.sql("""
                        INSERT INTO alerts (alert_id, transaction_id, user_id, card_id, detector, issue_type,
                                            explanation, occurred_at, detected_at, published_at)
                        VALUES (:alertId, :id, :user, :card, 'duplicate-charge', 'DUPLICATE_CHARGE', 'test', :time,
                                now(), now())
                        """)
                .param("alertId", UUID.randomUUID()).param("id", transactionId).param("user", user)
                .param("card", card).param("time", OffsetDateTime.parse(occurredAt))
                .update();
    }
}
