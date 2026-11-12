package io.budget.watchdog.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.MessageJson;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

/**
 * A small HTTP API for controlling the running simulation, built on the JDK's own HTTP server (no extra library).
 * The dashboard reaches it through nginx at /api/simulator/*. It has no authentication, like the rest of the API:
 * Compose doesn't publish its port, and nginx only listens on 127.0.0.1.
 *
 * <pre>
 *   GET  /api/simulator/status   what the clock is doing
 *   POST /api/simulator/pause    freeze simulated time
 *   POST /api/simulator/resume   continue from the same simulated moment
 *   POST /api/simulator/speed    {"daysPerMinute": 7}, one of {@link #SPEEDS}
 *   GET  /api/simulator/scenarios   whether demo scenarios are turned on, and which there are
 *   POST /api/simulator/scenarios   {"type": "DUPLICATE_CHARGE"}: publish that scenario now; answers with the run
 * </pre>
 *
 * Every answer is the new status as JSON, or {@code {"status": 400, "detail": "..."}} for a bad request.
 */
final class ControlServer {

    private static final Logger log = LoggerFactory.getLogger(ControlServer.class);

    /** The speeds the dashboard offers, in simulated days per real minute. "Max" stays available from SIM_DAYS_PER_MINUTE. */
    static final List<Integer> SPEEDS = List.of(1, 7, 30, 90, 365);

    /**
     * {@code daysPerMinute} is null at "max". {@code speeds} lists what can be chosen, so the dashboard needn't.
     * {@code published} counts this process only. {@code resumedFrom} is where it continued an earlier run, or null
     * for a new run. {@code pausedItself} is true while it's paused because {@code pauseAfterDays} ran out.
     */
    record StatusResponse(String state, Double daysPerMinute, Instant simulatedNow, long published,
            List<Integer> speeds, Instant resumedFrom, int pauseAfterDays, boolean pausedItself) {}

    record SpeedRequest(double daysPerMinute) {}

    record Problem(int status, String detail) {}

    record ScenarioRequest(IssueType type) {}

    record ScenariosResponse(boolean enabled, List<IssueType> types) {}

    private final HttpServer server;
    private final SimClock clock;
    private final Supplier<SimulatorRunner.Progress> progress;
    private final DemoRunner demos;

    ControlServer(int port, SimClock clock, Supplier<SimulatorRunner.Progress> progress, DemoRunner demos)
            throws IOException {
        this.clock = clock;
        this.progress = progress;
        this.demos = demos;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/simulator/", this::handle);
    }

    void start() {
        server.start();
        log.info("Control API listening on port {}", port());
    }

    void stop() {
        server.stop(0);
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String action = exchange.getRequestURI().getPath().substring("/api/simulator/".length());
            String method = exchange.getRequestMethod();
            // Every change must be sent as JSON. A web page on another site can make a browser send a plain form
            // post to localhost without asking, but not one with this content type: the browser first asks
            // permission (a "preflight" request), and this server never grants it. So only the dashboard's own page,
            // or a tool like curl, can pause the simulator or run a scenario.
            if (method.equals("POST") && !isJson(exchange)) {
                send(exchange, 415, new Problem(415, "send POST requests with Content-Type: application/json"));
                return;
            }
            switch (action) {
                case "status" -> requireThen(exchange, method, "GET", () -> { });
                case "pause" -> requireThen(exchange, method, "POST", clock::pause);
                case "resume" -> requireThen(exchange, method, "POST", clock::resume);
                case "speed" -> {
                    if (!method.equals("POST")) {
                        send(exchange, 405, new Problem(405, "use POST for speed"));
                        return;
                    }
                    Double speed = readSpeed(exchange);
                    if (speed == null) {
                        return; // readSpeed already answered 400
                    }
                    clock.setSpeed(speed);
                    send(exchange, 200, status());
                }
                case "scenarios" -> scenarios(exchange, method);
                default -> send(exchange, 404, new Problem(404, "no such control: " + action));
            }
        }
    }

    private void scenarios(HttpExchange exchange, String method) throws IOException {
        if (method.equals("GET")) {
            send(exchange, 200, new ScenariosResponse(demos.enabled(), DemoScenarios.TYPES));
            return;
        }
        if (!method.equals("POST")) {
            send(exchange, 405, new Problem(405, "use GET or POST for scenarios"));
            return;
        }
        if (!demos.enabled()) {
            send(exchange, 403, new Problem(403,
                    "Demo scenarios are turned off on this simulator. Start it with SIM_SCENARIOS_ENABLED=true."));
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        IssueType type;
        try {
            type = MessageJson.read(body, ScenarioRequest.class).type();
        } catch (JacksonException e) {
            type = null;
        }
        if (type == null || !DemoScenarios.TYPES.contains(type)) {
            send(exchange, 400, new Problem(400, "send {\"type\": t}, with t one of " + DemoScenarios.TYPES));
            return;
        }
        send(exchange, 200, demos.run(type));
    }

    private static boolean isJson(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    /** Runs {@code change} and answers with the new status, if the method is right. */
    private void requireThen(HttpExchange exchange, String method, String expected, Runnable change) throws IOException {
        if (!method.equals(expected)) {
            send(exchange, 405, new Problem(405, "use " + expected));
            return;
        }
        change.run();
        send(exchange, 200, status());
    }

    private Double readSpeed(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        double speed;
        try {
            speed = MessageJson.read(body, SpeedRequest.class).daysPerMinute();
        } catch (JacksonException e) {
            send(exchange, 400, new Problem(400, "send {\"daysPerMinute\": n}, with n one of " + SPEEDS));
            return null;
        }
        if (SPEEDS.stream().noneMatch(allowed -> allowed == speed)) {
            send(exchange, 400, new Problem(400, "daysPerMinute must be one of " + SPEEDS + ", got " + speed));
            return null;
        }
        return speed;
    }

    private StatusResponse status() {
        SimClock.Status status = clock.status();
        SimulatorRunner.Progress run = progress.get();
        return new StatusResponse(status.paused() ? "PAUSED" : "RUNNING", status.daysPerMinute(),
                status.simulatedNow(), run.published(), SPEEDS, run.resumedFrom(), run.pauseAfterDays(),
                run.pausedItself());
    }

    private static void send(HttpExchange exchange, int code, Object body) throws IOException {
        byte[] json = MessageJson.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, json.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(json);
        }
    }
}
