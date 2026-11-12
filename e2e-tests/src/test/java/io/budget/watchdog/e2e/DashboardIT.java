package io.budget.watchdog.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The dashboard in a real (headless) Chrome, against the running Compose stack: nginx, the watchdog, the simulator,
 * Kafka, and Postgres. Start the stack first and give it about two minutes, so there are alerts and monthly charges:
 *
 * <pre>
 *   docker compose up -d --build
 *   ./mvnw -Pe2e -pl e2e-tests verify
 * </pre>
 *
 * The tests use whatever the simulator has produced, so they check behavior (a new row appears, a filter filters,
 * a pause holds the count still), never exact numbers.
 */
class DashboardIT {

    private static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8083");
    private static final Dimension DESKTOP = new Dimension(1280, 900);
    private static final Dimension PHONE = new Dimension(390, 844);
    private static final List<String> VIEWS = List.of("overview", "feed", "alerts", "analytics", "recurring", "scenarios", "ml");

    private static WebDriver browser;
    private static WebDriverWait wait;

    @BeforeAll
    static void openBrowser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--window-size=1280,900");
        browser = new ChromeDriver(options);
        wait = new WebDriverWait(browser, Duration.ofSeconds(20));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.quit();
        }
    }

    /** Every test starts from a running simulator at the demo's speed, whatever the last test or person left. */
    @BeforeEach
    void simulatorRunning() throws Exception {
        post("/api/simulator/speed", "{\"daysPerMinute\": 30}");
        post("/api/simulator/resume", "");
        browser.manage().window().setSize(DESKTOP);
    }

    @Test
    void theOverviewShowsTotalsSpeedAndEveryRule() {
        open("overview");

        assertThat(number("total-transactions")).isPositive();
        assertThat(text("throughput")).endsWith("/s");
        assertThat(text("connection-status")).isEqualTo("Live");
        List<WebElement> rules = wait.until(
                d -> d.findElements(By.cssSelector("section[aria-labelledby=detection-title] tbody tr")));
        assertThat(rules).extracting(row -> row.findElement(By.tagName("th")).getText())
                .containsExactly("Card testing", "Duplicate charge", "New country", "Price hike", "Spending spike",
                        "Trial conversion");
    }

    @Test
    void newTransactionsAppearInTheLiveFeedOnTheirOwn() {
        open("feed");
        String newestBefore = firstRowOf("feed-table").getText();

        // No reload: the row has to arrive over the live stream.
        wait.until(d -> !firstRowOf("feed-table").getText().equals(newestBefore));
        assertThat(browser.findElements(By.cssSelector("[data-testid=feed-table] tbody tr"))).hasSizeGreaterThan(1);
    }

    @Test
    void pausingHoldsTheTotalsStillAndResumingMovesThemAgain() throws Exception {
        open("overview");
        WebElement toggle = wait.until(d -> d.findElement(By.cssSelector("[data-testid=simulator-toggle]")));
        assertThat(text("simulator-state")).isEqualTo("Simulator running");
        assertThat(toggle.getText()).isEqualTo("Pause");

        toggle.click();
        wait.until(d -> text("simulator-state").equals("Simulator paused"));
        assertThat(browser.findElement(By.cssSelector("[data-testid=simulator-toggle]")).getText()).isEqualTo("Resume");
        // The simulator itself says so, not just the button.
        assertThat(get("/api/simulator/status")).contains("\"state\":\"PAUSED\"");

        // A few messages were already in Kafka when the clock stopped. Once the watchdog has stored those, the total
        // must hold still: several reads in a row give the same number (running, it grows by about 4 a second).
        long whilePaused = awaitSteadyTotal();
        wait.until(d -> number("total-transactions") == whilePaused); // and the page catches up with it
        assertThat(storedTotal()).isEqualTo(whilePaused);

        browser.findElement(By.cssSelector("[data-testid=simulator-toggle]")).click();
        wait.until(d -> text("simulator-state").equals("Simulator running"));
        assertThat(get("/api/simulator/status")).contains("\"state\":\"RUNNING\"");
        // First the watchdog stores something new, then the page shows it (it asks every 5 seconds).
        wait.until(d -> storedTotal() > whilePaused);
        wait.until(d -> number("total-transactions") > whilePaused);
    }

    @Test
    void changingTheSpeedIsConfirmedByTheSimulator() throws Exception {
        open("overview");
        Select speed = new Select(wait.until(d -> d.findElement(By.cssSelector("[data-testid=simulator-speed]"))));
        assertThat(speed.getOptions()).extracting(WebElement::getText)
                .containsExactly("1 day/min", "7 days/min", "30 days/min", "90 days/min", "365 days/min");

        speed.selectByVisibleText("90 days/min");

        wait.until(d -> statusContains("\"daysPerMinute\":90.0"));
    }

    @Test
    void theAlertsFilterShowsOnlyThatRule() throws Exception {
        // The test makes its own data instead of hoping the simulator has produced some: one duplicate-charge alert
        // and one of another rule, through the real pipeline. On a stack that's two minutes old there may be neither.
        post("/api/simulator/scenarios", "{\"type\": \"DUPLICATE_CHARGE\"}");
        post("/api/simulator/scenarios", "{\"type\": \"PRICE_HIKE\"}");
        open("alerts");
        wait.until(d -> rules(d).contains("duplicate-charge") && rules(d).contains("price-hike"));

        new Select(browser.findElement(By.id("rule-filter"))).selectByValue("duplicate-charge");

        // Not just "every row matches", which is also true of the empty table shown while the filtered list loads.
        wait.until(d -> !rules(d).isEmpty() && rules(d).stream().allMatch("duplicate-charge"::equals));
        List<WebElement> rows = browser.findElements(By.cssSelector("[data-testid=alerts-table] tbody tr"));
        // Each alert carries the rule's own explanation, like "... charged $140.84 twice, 24 seconds apart."
        assertThat(rows.getFirst().getText()).contains("charged");
    }

    @Test
    void analyticsDrawsItsChartsFromRealData() {
        open("analytics");

        // Three column charts (transactions, spending, alerts), each with at least one drawn column.
        List<WebElement> charts = wait.until(d -> {
            List<WebElement> found = d.findElements(By.cssSelector(".chart-stack svg"));
            return found.size() == 3 ? found : null;
        });
        assertThat(charts).allSatisfy(chart -> assertThat(chart.findElements(By.cssSelector("path.chart-bar"))).isNotEmpty());
        assertThat(wait.until(d -> d.findElement(By.id("speed-title"))).getText()).isEqualTo("Pipeline speed (real time)");
    }

    @Test
    void recurringListsEachUsersMonthlyCharges() {
        open("recurring");
        wait.until(d -> !d.findElements(By.cssSelector("[data-testid=recurring-table] tbody tr")).isEmpty());
        // The view opens on a simulated person, not on the demo user that scenario runs create.
        Select user = new Select(browser.findElement(By.id("recurring-user")));
        assertThat(user.getFirstSelectedOption().getDomAttribute("value")).isEqualTo("user-0001");
        String firstUsersCharges = browser.findElement(By.cssSelector("[data-testid=recurring-table] tbody")).getText();

        user.selectByValue("user-0002");

        wait.until(d -> {
            List<WebElement> body = d.findElements(By.cssSelector("[data-testid=recurring-table] tbody"));
            return !body.isEmpty() && !body.getFirst().getText().equals(firstUsersCharges);
        });
    }

    @Test
    void theMlComparisonShowsTheSavedExperimentForEveryApproach() {
        open("ml");

        assertThat(text("ml-headline")).startsWith("On the held-out run, the");
        List<WebElement> rows = wait.until(d -> d.findElements(By.cssSelector("[data-testid=ml-scores-table] tbody tr")));
        assertThat(rows).extracting(row -> row.findElement(By.tagName("th")).getText())
                .startsWith("Six rules (Java)", "Logistic regression", "Random forest");
        // Every approach has a precision and a recall as a percent, read from the saved results file.
        assertThat(rows).allSatisfy(row -> assertThat(row.findElements(By.tagName("td")).get(0).getText()).endsWith("%"));
    }

    @Test
    void noViewScrollsSidewaysOnAPhone() {
        browser.manage().window().setSize(PHONE);
        for (String view : VIEWS) {
            open(view);
            wait.until(d -> d.findElements(By.cssSelector("[aria-busy=true]")).isEmpty());
            // Expandable tables too: opened, a wide one must scroll inside its panel, not widen the page.
            ((JavascriptExecutor) browser).executeScript(
                    "document.querySelectorAll('details').forEach(details => { details.open = true; })");
            Number overflow = (Number) ((JavascriptExecutor) browser).executeScript(
                    "return document.documentElement.scrollWidth - document.documentElement.clientWidth");
            assertThat(overflow.intValue()).as("sideways overflow in pixels on the %s view", view).isZero();
        }
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    private static void open(String view) {
        browser.get(BASE_URL + "/#" + view);
        browser.navigate().refresh(); // a hash-only change wouldn't reload the page between tests
        wait.until(d -> d.findElement(By.cssSelector("a.tab[aria-current=page]")).getDomAttribute("href").equals("#" + view));
    }

    /** The rule of each row in the alerts table, re-read each time so a re-render can't leave a stale element. */
    private static List<String> rules(WebDriver driver) {
        return driver.findElements(By.cssSelector("[data-testid=alerts-table] tbody tr")).stream()
                .map(row -> row.getDomAttribute("data-rule")).toList();
    }

    /** How many transactions the watchdog has stored, asked directly (the page only asks every 5 seconds). */
    private static long storedTotal() {
        try {
            Map<String, Object> stats = new Json().toType(get("/api/stats"), Json.MAP_TYPE);
            return ((Number) ((Map<?, ?>) stats.get("totals")).get("transactions")).longValue();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits until the stored total stops changing: the same number on four reads, half a second apart. */
    private static long awaitSteadyTotal() {
        long[] last = {-1};
        int[] same = {0};
        return new WebDriverWait(browser, Duration.ofSeconds(30)).pollingEvery(Duration.ofMillis(500)).until(d -> {
            long now = storedTotal();
            same[0] = now == last[0] ? same[0] + 1 : 0;
            last[0] = now;
            return same[0] >= 3 ? now : null;
        });
    }

    private static String text(String testId) {
        return wait.until(d -> d.findElement(By.cssSelector("[data-testid=" + testId + "]"))).getText();
    }

    /** A metric like "3,755" as a number. */
    private static long number(String testId) {
        return Long.parseLong(text(testId).replace(",", ""));
    }

    private static WebElement firstRowOf(String tableTestId) {
        return wait.until(d -> d.findElement(By.cssSelector("[data-testid=" + tableTestId + "] tbody tr")));
    }

    private static boolean statusContains(String expected) {
        try {
            return get("/api/simulator/status").contains(expected);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(BASE_URL + path)).GET().build());
    }

    private static void post(String path, String json) throws IOException, InterruptedException {
        send(HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private static String send(HttpRequest request) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s", request.method(), request.uri()).isEqualTo(200);
            return response.body();
        }
    }
}
