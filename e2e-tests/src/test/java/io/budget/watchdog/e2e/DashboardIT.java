package io.budget.watchdog.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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
    private static final List<String> VIEWS = List.of("overview", "feed", "alerts", "analytics", "recurring");

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

        // Whatever was already on its way gets stored; after that the total must hold still.
        Thread.sleep(8_000);
        long whilePaused = number("total-transactions");
        Thread.sleep(8_000); // longer than the overview's 5-second refresh
        assertThat(number("total-transactions")).isEqualTo(whilePaused);

        browser.findElement(By.cssSelector("[data-testid=simulator-toggle]")).click();
        wait.until(d -> text("simulator-state").equals("Simulator running"));
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
    void theAlertsFilterShowsOnlyThatRule() {
        open("alerts");
        wait.until(d -> !d.findElements(By.cssSelector("[data-testid=alerts-table] tbody tr")).isEmpty());

        new Select(browser.findElement(By.id("rule-filter"))).selectByValue("duplicate-charge");

        wait.until(d -> d.findElements(By.cssSelector("[data-testid=alerts-table] tbody tr")).stream()
                .allMatch(row -> "duplicate-charge".equals(row.getDomAttribute("data-rule"))));
        List<WebElement> rows = browser.findElements(By.cssSelector("[data-testid=alerts-table] tbody tr"));
        assertThat(rows).isNotEmpty();
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
        String firstUsersCharges = browser.findElement(By.cssSelector("[data-testid=recurring-table] tbody")).getText();

        new Select(browser.findElement(By.id("recurring-user"))).selectByIndex(1);

        wait.until(d -> {
            List<WebElement> body = d.findElements(By.cssSelector("[data-testid=recurring-table] tbody"));
            return !body.isEmpty() && !body.getFirst().getText().equals(firstUsersCharges);
        });
    }

    @Test
    void noViewScrollsSidewaysOnAPhone() {
        browser.manage().window().setSize(PHONE);
        for (String view : VIEWS) {
            open(view);
            wait.until(d -> d.findElements(By.cssSelector("[aria-busy=true]")).isEmpty());
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
