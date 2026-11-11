package io.budget.watchdog.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.budget.watchdog.schema.MessageJson;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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

    private final HttpServer server;
    private final SimClock clock;
    private final Supplier<SimulatorRunner.Progress> progress;

    ControlServer(int port, SimClock clock, Supplier<SimulatorRunner.Progress> progress) throws IOException {
        this.clock = clock;
        this.progress = progress;
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
                default -> send(exchange, 404, new Problem(404, "no such control: " + action));
            }
        }
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
