package io.budget.watchdog.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The demo scenarios against the running Compose stack. Nothing here is faked: a request asks the simulator to
 * publish a scenario to Kafka, the watchdog consumes and stores it and runs its real rules, and the test reads the
 * outcome back from the watchdog's API by scenario ID. This is the test that proves each scenario's script really
 * meets its rule's conditions, since the scripts live in the simulator and the rules in the watchdog.
 *
 * <pre>
 *   docker compose up -d --build
 *   ./mvnw -Pe2e -pl e2e-tests verify
 * </pre>
 */
class ScenariosIT {

    private static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8083");
    private static final Json JSON = new Json();

    private static WebDriver browser;
    private static WebDriverWait wait;

    @BeforeAll
    static void openBrowser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--window-size=1280,900");
        browser = new ChromeDriver(options);
        wait = new WebDriverWait(browser, Duration.ofSeconds(25));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.quit();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "CARD_TESTING, card-testing, Someone may be testing your card number",
            "DUPLICATE_CHARGE, duplicate-charge, 'Whole Foods Market charged $64.27 twice, 24 seconds apart.'",
            "NEW_COUNTRY, new-country, (France)",
            "PRICE_HIKE, price-hike, 'Netflix charged $18.49, up from $15.49 last month.'",
            "SPENDING_SPIKE, spending-spike, You've spent $213.90 on dining today",
            "TRIAL_CONVERSION, trial-conversion, 'Peacock charged $10.99, 14 days after a $0.00 charge.'"})
    void everyScenarioMakesExactlyItsOwnRuleFire(String type, String detector, String evidence) throws Exception {
        Map<String, Object> run = runScenario(type);
        assertThat(run.get("expectedDetector")).isEqualTo(detector);
        assertThat((String) run.get("cardId")).startsWith("demo-");

        Map<String, Object> result = awaitStored(run);

        // One alert, from the expected rule, on the run's last transaction, saying why in the rule's own words.
        List<Map<String, Object>> alerts = list(result, "alerts");
        assertThat(alerts).as("alerts for %s", type).hasSize(1);
        Map<String, Object> alert = alerts.getFirst();
        assertThat(alert.get("detector")).isEqualTo(detector);
        assertThat((String) alert.get("explanation")).contains(evidence);
        assertThat(alert.get("cardId")).isEqualTo(run.get("cardId"));
        List<Map<String, Object>> steps = list(run, "steps");
        if (!type.equals("SPENDING_SPIKE")) { // the spike is crossed by the second of today's three orders
            assertThat(alert.get("transactionId")).isEqualTo(steps.getLast().get("transactionId"));
        }
        // Everything stored is marked as demo data.
        assertThat(list(result, "transactions")).allSatisfy(tx -> {
            assertThat(tx.get("source")).isEqualTo("DEMO");
            assertThat(tx.get("userId")).isEqualTo("demo-user");
        });
    }

    @Test
    void runningAScenarioAgainUsesNewIdsAndIsDetectedAgain() throws Exception {
        Map<String, Object> first = runScenario("DUPLICATE_CHARGE");
        Map<String, Object> second = runScenario("DUPLICATE_CHARGE");

        assertThat(second.get("scenarioId")).isNotEqualTo(first.get("scenarioId"));
        assertThat(second.get("cardId")).isNotEqualTo(first.get("cardId"));
        assertThat(ids(list(second, "steps"))).doesNotContainAnyElementsOf(ids(list(first, "steps")));
        // Both runs were stored in full: an ID repeated from the first run would have been skipped as already stored.
        assertThat(list(awaitStored(first), "alerts")).hasSize(1);
        assertThat(list(awaitStored(second), "alerts")).hasSize(1);
    }

    @Test
    void theScenariosTabRunsAScenarioAndShowsWhatTheWatchdogSaid() {
        browser.get(BASE_URL + "/#scenarios");
        browser.navigate().refresh();
        WebElement card = wait.until(d -> d.findElement(By.cssSelector("[data-testid=scenario-price-hike]")));
        assertThat(card.findElement(By.cssSelector("[data-testid=scenario-state]")).getText()).isEqualTo("Not run yet");

        card.findElement(By.cssSelector("[data-testid=run-scenario]")).click();

        wait.until(d -> card.findElement(By.cssSelector("[data-testid=scenario-state]")).getText().equals("Detected"));
        assertThat(card.findElement(By.cssSelector("[data-testid=scenario-verdict]")).getText())
                .isEqualTo("The price hike rule fired.");
        assertThat(card.findElement(By.cssSelector("[data-testid=scenario-alerts]")).getText())
                .contains("Netflix charged $18.49, up from $15.49 last month.");
        // The hiked charge is listed as stored and flagged; the two earlier months are folded away as history.
        List<WebElement> sent = card.findElements(By.cssSelector("[data-testid=scenario-steps] tbody tr"));
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getText()).contains("Netflix", "$18.49", "Stored", "Price hike");
        assertThat(card.findElement(By.cssSelector("[data-testid=run-scenario]")).getText()).isEqualTo("Run again");

        // The link opens the Alerts view on that alert: only the demo user's price-hike alerts, with this one marked.
        card.findElement(By.linkText("See it in Alerts")).click();
        wait.until(d -> !d.findElements(By.cssSelector("[data-testid=alerts-table] tr.row-highlight")).isEmpty());
        assertThat(browser.findElement(By.cssSelector("[data-testid=alerts-table] tr.row-highlight")).getText())
                .contains("Netflix charged $18.49, up from $15.49 last month.");
        assertThat(browser.findElement(By.cssSelector("[data-testid=alerts-user-filter]")).getText()).contains("demo-user");
        assertThat(browser.findElements(By.cssSelector("[data-testid=alerts-table] tbody tr")))
                .allSatisfy(row -> assertThat(row.getDomAttribute("data-rule")).isEqualTo("price-hike"));

        // Coming back, the run is still shown.
        browser.findElement(By.cssSelector("a.tab[href='#scenarios']")).click();
        WebElement again = wait.until(d -> d.findElement(By.cssSelector("[data-testid=scenario-price-hike]")));
        assertThat(again.findElement(By.cssSelector("[data-testid=scenario-state]")).getText()).isEqualTo("Detected");
    }

    @Test
    void aScenarioTransactionShowsUpInTheLiveFeed() throws Exception {
        Map<String, Object> run = runScenario("DUPLICATE_CHARGE");
        awaitStored(run);
        String lastId = (String) list(run, "steps").getLast().get("transactionId");

        browser.get(BASE_URL + "/#feed?highlight=" + lastId);
        browser.navigate().refresh();

        WebElement row = wait.until(d -> d.findElement(By.cssSelector("[data-testid=feed-table] tr.row-highlight")));
        assertThat(row.getText()).contains((String) run.get("cardId"), "scenario", "Whole Foods Market", "$64.27",
                "Duplicate charge");
    }

    @Test
    void aBadScenarioRequestIsRefusedWithAReason() throws Exception {
        HttpResponse<String> response = post("/api/simulator/scenarios", "{\"type\": \"MONEY_LAUNDERING\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("CARD_TESTING");
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    /** Asks the simulator, through nginx like the dashboard does, to publish one scenario. */
    private static Map<String, Object> runScenario(String type) throws Exception {
        HttpResponse<String> response = post("/api/simulator/scenarios", "{\"type\": \"" + type + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.toType(response.body(), Json.MAP_TYPE);
    }

    /** Polls the watchdog until it has stored every transaction of the run, and returns what it says about it. */
    private static Map<String, Object> awaitStored(Map<String, Object> run) throws Exception {
        Set<Object> expected = ids(list(run, "steps"));
        Map<String, Object> result = Map.of();
        for (int tries = 0; tries < 100; tries++) {
            result = JSON.toType(get("/api/scenarios/" + run.get("scenarioId")), Json.MAP_TYPE);
            if (ids(list(result, "transactions")).equals(expected)) {
                return result;
            }
            Thread.sleep(200);
        }
        assertThat(ids(list(result, "transactions"))).as("transactions the watchdog stored for the run").isEqualTo(expected);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> json, String field) {
        return (List<Map<String, Object>>) json.get(field);
    }

    private static Set<Object> ids(List<Map<String, Object>> items) {
        Set<Object> ids = new HashSet<>();
        items.forEach(item -> ids.add(item.get("transactionId")));
        return ids;
    }

    private static String get(String path) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
            return response.body();
        }
    }

    private static HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create(BASE_URL + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
