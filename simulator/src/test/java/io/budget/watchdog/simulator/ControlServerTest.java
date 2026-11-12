package io.budget.watchdog.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.MessageJson;
import io.budget.watchdog.schema.Transaction;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The control API over real HTTP, against a clock that isn't running a simulation. */
class ControlServerTest {

    private static final Instant SIM_START = Instant.parse("2026-01-01T00:00:00Z");

    private final SimClock clock = new SimClock(SIM_START, 30, InstantSource.system(), false);
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Transaction> published = new CopyOnWriteArrayList<>(); // filled by the server's thread
    private ControlServer server;

    @BeforeEach
    void start() throws IOException {
        // port 0: any free port. The run: 42 published so far, continuing an earlier run, pausing every 365 days.
        server = new ControlServer(0, clock, () -> new SimulatorRunner.Progress(42, SIM_START, 365, false), demos(true));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
        http.close();
    }

    @Test
    void statusSaysWhatTheClockIsDoing() throws Exception {
        HttpResponse<String> response = call("GET", "status", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        ControlServer.StatusResponse status = MessageJson.read(response.body(), ControlServer.StatusResponse.class);
        assertThat(status.state()).isEqualTo("RUNNING");
        assertThat(status.daysPerMinute()).isEqualTo(30.0);
        assertThat(status.published()).isEqualTo(42);
        assertThat(status.speeds()).containsExactly(1, 7, 30, 90, 365);
        assertThat(status.resumedFrom()).isEqualTo(SIM_START);
        assertThat(status.pauseAfterDays()).isEqualTo(365);
        assertThat(status.pausedItself()).isFalse();
    }

    @Test
    void pauseAndResume() throws Exception {
        assertThat(state(call("POST", "pause", null))).isEqualTo("PAUSED");
        Instant frozen = clock.simulatedNow();
        Thread.sleep(50);
        assertThat(clock.simulatedNow()).isEqualTo(frozen);

        assertThat(state(call("POST", "resume", null))).isEqualTo("RUNNING");
    }

    @Test
    void speedTakesOneOfTheOfferedValues() throws Exception {
        HttpResponse<String> response = call("POST", "speed", "{\"daysPerMinute\": 7}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(clock.status().daysPerMinute()).isEqualTo(7.0);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            {"daysPerMinute": 8}      | daysPerMinute must be one of [1, 7, 30, 90, 365], got 8.0
            {"daysPerMinute": "fast"} | send {"daysPerMinute": n}
            {}                        | send {"daysPerMinute": n}
            not json                  | send {"daysPerMinute": n}
            """)
    void aBadSpeedIsA400ThatSaysWhy(String body, String detail) throws Exception {
        HttpResponse<String> response = call("POST", "speed", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"status\":400").contains(detail.replace("\"", "\\\""));
        assertThat(clock.status().daysPerMinute()).isEqualTo(30.0); // unchanged
    }

    @Test
    void theWrongMethodIsA405AndAnUnknownControlA404() throws Exception {
        assertThat(call("GET", "pause", null).statusCode()).isEqualTo(405);
        assertThat(call("POST", "status", null).statusCode()).isEqualTo(405);
        assertThat(call("POST", "rewind", null).statusCode()).isEqualTo(404);
        assertThat(clock.status().paused()).isFalse();
    }

    @Test
    void scenariosListsWhatCanBeRun() throws Exception {
        HttpResponse<String> response = call("GET", "scenarios", null);

        assertThat(response.statusCode()).isEqualTo(200);
        ControlServer.ScenariosResponse scenarios = MessageJson.read(response.body(), ControlServer.ScenariosResponse.class);
        assertThat(scenarios.enabled()).isTrue();
        assertThat(scenarios.types()).containsExactlyElementsOf(DemoScenarios.TYPES);
    }

    @Test
    void runningAScenarioPublishesItAndAnswersWithTheRun() throws Exception {
        HttpResponse<String> response = call("POST", "scenarios", "{\"type\": \"DUPLICATE_CHARGE\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        DemoScenarios.Run run = MessageJson.read(response.body(), DemoScenarios.Run.class);
        assertThat(run.type()).isEqualTo(IssueType.DUPLICATE_CHARGE);
        assertThat(run.expectedDetector()).isEqualTo("duplicate-charge");
        assertThat(run.steps()).hasSize(2);
        // What the answer lists is exactly what went to Kafka, in order, on the run's own card.
        assertThat(published).extracting(Transaction::transactionId)
                .containsExactlyElementsOf(run.steps().stream().map(DemoScenarios.Step::transactionId).toList());
        assertThat(published).allSatisfy(tx -> {
            assertThat(tx.cardId()).isEqualTo(run.cardId());
            assertThat(tx.source()).isEqualTo(Transaction.DEMO_SOURCE);
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"type\": \"NONE\"}",
            "{\"type\": \"MONEY_LAUNDERING\"}",
            "{}",
            "not json"})
    void aBadScenarioRequestIsA400AndPublishesNothing(String body) throws Exception {
        HttpResponse<String> response = call("POST", "scenarios", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("CARD_TESTING");
        assertThat(published).isEmpty();
    }

    @Test
    void scenariosAreRefusedUnlessTurnedOn() throws Exception {
        server.stop();
        server = new ControlServer(0, clock, () -> new SimulatorRunner.Progress(0, null, 0, false), demos(false));
        server.start();

        assertThat(MessageJson.read(call("GET", "scenarios", null).body(), ControlServer.ScenariosResponse.class).enabled())
                .isFalse();
        HttpResponse<String> response = call("POST", "scenarios", "{\"type\": \"DUPLICATE_CHARGE\"}");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("SIM_SCENARIOS_ENABLED");
        assertThat(published).isEmpty();
    }

    private DemoRunner demos(boolean enabled) {
        return new DemoRunner(enabled, clock, (transaction, label) -> published.add(transaction), InstantSource.system(),
                UUID::randomUUID);
    }

    @ParameterizedTest
    @CsvSource({"pause, text/plain", "scenarios, text/plain", "scenarios, application/x-www-form-urlencoded", "pause,"})
    void aPostThatIsNotJsonIsRefusedAndChangesNothing(String control, String contentType) throws Exception {
        // What another website could make a browser send to localhost without permission: a form-style post.
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/api/simulator/" + control))
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\": \"DUPLICATE_CHARGE\"}"));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(published).isEmpty();
        assertThat(clock.status().paused()).isFalse();
    }

    private HttpResponse<String> call(String method, String control, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/simulator/" + control))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (method.equals("POST")) {
            request.header("Content-Type", "application/json"); // like the dashboard
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String state(HttpResponse<String> response) {
        return MessageJson.read(response.body(), ControlServer.StatusResponse.class).state();
    }
}
