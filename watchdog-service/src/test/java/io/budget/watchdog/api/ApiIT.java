package io.budget.watchdog.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

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
 * The REST API over real HTTP, on rows written straight to Postgres for one user of its own, so every expected number
 * here can be worked out by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.register(registry);
    }

    private static final String BUDGET_IO = "http://localhost:5173";

    private final String user = "user-" + UUID.randomUUID();
    private final String card = user.replace("user", "card");

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int port;

    @BeforeAll
    void aUserWithThreeMonthsOfCharges() {
        // Netflix every month at the same price: an active recurring charge. March is stored first on purpose, like a
        // late arrival: the recurring charges list reads history in event-time order, so it still sees Jan, Feb, Mar.
        UUID netflixMarch = stored("Netflix", "SUBSCRIPTIONS", 1_549, "2031-03-05T08:00:00Z");
        stored("Netflix", "SUBSCRIPTIONS", 1_549, "2031-01-05T08:00:00Z");
        stored("Netflix", "SUBSCRIPTIONS", 1_549, "2031-02-05T08:00:00Z");
        // Spotify steady for two months, then more: a price hike.
        stored("Spotify", "SUBSCRIPTIONS", 1_199, "2031-01-01T08:00:00Z");
        stored("Spotify", "SUBSCRIPTIONS", 1_199, "2031-01-31T08:00:00Z");
        UUID spotifyHike = stored("Spotify", "SUBSCRIPTIONS", 1_399, "2031-03-02T08:00:00Z");
        // Electricity that changes every month: recurring, but its price varies.
        stored("City Electric", "UTILITIES", 8_412, "2031-01-03T08:00:00Z");
        stored("City Electric", "UTILITIES", 9_377, "2031-02-03T08:00:00Z");
        // March, for the spending summary, with both of its edges.
        stored("Northwind Payroll", "INCOME", 524_000, "2031-03-01T06:00:00Z");
        stored("Maple Street Apartments", "RENT", 145_000, "2031-03-01T00:00:00Z");
        stored("Corner Market", "GROCERIES", 3_125, "2031-03-04T18:00:00Z");
        stored("Blue Bottle Coffee", "DINING", 1_874, "2031-03-31T23:59:59Z");
        stored("Blue Bottle Coffee", "DINING", 999, "2031-04-01T00:00:00Z"); // April: the newest transaction

        alert(spotifyHike, "price-hike", "PRICE_HIKE", "Spotify charged $13.99, up from $11.99 last month.");
        alert(netflixMarch, "duplicate-charge", "DUPLICATE_CHARGE", "Netflix charged $15.49 twice, 40 seconds apart.");
    }

    @Test
    void transactionsAreNewestFirstAndLimited() {
        given().port(port).queryParam("userId", user).queryParam("limit", 3)
                .when().get("/api/transactions")
                .then().statusCode(200)
                .body("", hasSize(3))
                .body("merchant", contains("Blue Bottle Coffee", "Blue Bottle Coffee", "Netflix"))
                .body("[0].occurredAt", is("2031-04-01T00:00:00Z"))
                .body("[0].amountCents", is(999));
    }

    @Test
    void alertsCanBeFilteredByDetector() {
        given().port(port).queryParam("userId", user)
                .when().get("/api/alerts")
                .then().statusCode(200)
                .body("detector", contains("duplicate-charge", "price-hike")); // newest first: March 5, then March 2

        given().port(port).queryParam("userId", user).queryParam("detector", "price-hike")
                .when().get("/api/alerts")
                .then().statusCode(200)
                .body("", hasSize(1))
                .body("[0].explanation", is("Spotify charged $13.99, up from $11.99 last month."))
                .body("[0].cardId", is(card));
    }

    @Test
    void recurringChargesAreEveryChargeAMonthApart() {
        given().port(port).queryParam("userId", user)
                .when().get("/api/recurring")
                .then().statusCode(200)
                // City Electric's price moves (it would be VARIES), but its last charge was in February, more than a
                // month before the user's newest transaction, so timing wins: OVERDUE.
                .body("merchant", contains("City Electric", "Netflix", "Spotify"))
                .body("status", contains("OVERDUE", "ACTIVE", "PRICE_UP"))
                .body("[2].priceCents", is(1_399))
                .body("[2].previousPriceCents", is(1_199))
                .body("[1].nextExpectedAt", is("2031-04-02T08:00:00Z"))
                .body("[1].yearlyCostCents", is(18_588));
    }

    @Test
    void aMonthIsGroupedByBudgetIoLine() {
        given().port(port).queryParam("userId", user).queryParam("month", "2031-03")
                .when().get("/api/spending/summary")
                .then().statusCode(200)
                .body("month", is("2031-03"))
                .body("incomeCents", is(524_000))
                .body("spendingCents", is(145_000 + 3_125 + 1_874 + 1_549 + 1_399))
                .body("lines.line", contains("Rent/Mortgage", "Utilities", "Food", "Transportation", "Subscriptions",
                        "TP Fund", "Travel"))
                .body("lines.cents", contains(145_000, 0, 3_125 + 1_874, 0, 1_549 + 1_399, 0, 0));
    }

    @Test
    void anUnknownUserGetsEmptyResultsNotAnError() {
        String nobody = "user-" + UUID.randomUUID();
        given().port(port).queryParam("userId", nobody).when().get("/api/recurring")
                .then().statusCode(200).body("", hasSize(0));
        given().port(port).queryParam("userId", nobody).queryParam("month", "2031-03")
                .when().get("/api/spending/summary")
                .then().statusCode(200).body("spendingCents", is(0)).body("lines.cents", everyItem(is(0)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            /api/transactions?limit=abc                      | limit
            /api/transactions?limit=0                        | limit must be between 1 and 500
            /api/alerts?limit=501                            | limit must be between 1 and 500
            /api/alerts?detector=duplicate-charges           | unknown detector duplicate-charges
            /api/recurring                                   | userId is required
            /api/recurring?userId=                           | userId is required
            /api/spending/summary?month=2031-03              | userId is required
            /api/spending/summary?userId=user-0001           | month is required
            /api/spending/summary?userId=user-0001&month=2031-13 | month must look like 2026-03
            /api/spending/summary?userId=user-0001&month=March   | month must look like 2026-03
            """)
    void badParametersGetA400SayingWhatWasWrong(String path, String reason) {
        given().port(port).urlEncodingEnabled(false)
                .when().get(path)
                .then().statusCode(400)
                .contentType("application/problem+json")
                .body("status", is(400))
                .body("detail", containsString(reason));
    }

    @Test
    void budgetIoMayReadTheSpendingSummaryFromTheBrowser() {
        given().port(port)
                .header("Origin", BUDGET_IO)
                .header("Access-Control-Request-Method", "GET")
                .when().options("/api/spending/summary")
                .then().statusCode(200)
                .header("Access-Control-Allow-Origin", BUDGET_IO);

        given().port(port).header("Origin", BUDGET_IO)
                .queryParam("userId", user).queryParam("month", "2031-03")
                .when().get("/api/spending/summary")
                .then().statusCode(200)
                .header("Access-Control-Allow-Origin", BUDGET_IO);
    }

    @Test
    void otherOriginsAndOtherEndpointsGetNoCors() {
        given().port(port)
                .header("Origin", "http://example.com")
                .header("Access-Control-Request-Method", "GET")
                .when().options("/api/spending/summary")
                .then().statusCode(403);

        given().port(port).header("Origin", BUDGET_IO).queryParam("userId", user)
                .when().get("/api/transactions")
                .then().header("Access-Control-Allow-Origin", nullValue());
    }

    private UUID stored(String merchant, String category, long cents, String occurredAt) {
        UUID id = UUID.randomUUID();
        OffsetDateTime time = OffsetDateTime.parse(occurredAt);
        jdbc.sql("""
                        INSERT INTO transactions (transaction_id, source, user_id, card_id, merchant, category,
                                                  amount_cents, currency, country, occurred_at, published_at,
                                                  processed_at)
                        VALUES (:id, 'SIMULATOR', :user, :card, :merchant, :category, :cents, 'USD', 'US', :time,
                                now(), now())
                        """)
                .param("id", id).param("user", user).param("card", card).param("merchant", merchant)
                .param("category", category).param("cents", cents).param("time", time)
                .update();
        return id;
    }

    private void alert(UUID transactionId, String detector, String issueType, String explanation) {
        OffsetDateTime occurredAt = jdbc.sql("SELECT occurred_at FROM transactions WHERE transaction_id = :id")
                .param("id", transactionId).query(OffsetDateTime.class).single();
        jdbc.sql("""
                        INSERT INTO alerts (alert_id, transaction_id, user_id, card_id, detector, issue_type,
                                            explanation, occurred_at, detected_at, published_at)
                        VALUES (:alertId, :id, :user, :card, :detector, :issueType, :explanation, :occurredAt,
                                now(), now())
                        """)
                .param("alertId", UUID.randomUUID()).param("id", transactionId).param("user", user)
                .param("card", card).param("detector", detector).param("issueType", issueType)
                .param("explanation", explanation).param("occurredAt", occurredAt)
                .update();
    }
}
