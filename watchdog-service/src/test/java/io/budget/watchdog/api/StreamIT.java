package io.budget.watchdog.api;

import static io.budget.watchdog.TestKafka.charge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.budget.watchdog.IntegrationTestContainers;
import io.budget.watchdog.TestKafka;
import io.budget.watchdog.schema.Transaction;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A client connected to {@code /api/stream} sees new transactions and alerts as they're handled. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class StreamIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.register(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void aDuplicatedChargeShowsUpAsTwoTransactionsAndOneAlert() throws Exception {
        String card = TestKafka.newCard();
        Instant monday = Instant.parse("2026-03-02T18:57:21Z");
        Transaction original = charge(card, "Whole Foods Market", "US", 14_084, monday);
        Transaction duplicate = charge(card, "Whole Foods Market", "US", 14_084, monday.plusSeconds(24));
        List<String> lines = new CopyOnWriteArrayList<>();

        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stream")).build();
            // Returns once the headers arrive; the body keeps streaming, so read it on another thread.
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/event-stream"));
            Thread reader = Thread.ofVirtual().start(() -> response.body().forEach(lines::add));

            TestKafka.send(original, duplicate);

            await().atMost(Duration.ofSeconds(30)).until(() -> dataLinesAbout(lines, card).size() == 3);
            reader.interrupt();
            response.body().close();
        }

        // Server-Sent Events: an "event:" line names the kind, the next "data:" line carries the JSON.
        assertThat(lines).contains("event:transaction", "event:alert");
        List<String> data = dataLinesAbout(lines, card);
        assertThat(data.get(0)).contains(original.transactionId().toString());
        assertThat(data.get(1)).contains(duplicate.transactionId().toString());
        assertThat(data.get(2)).contains("\"detector\":\"duplicate-charge\"")
                .contains("Whole Foods Market charged $140.84 twice, 24 seconds apart.");
    }

    private static List<String> dataLinesAbout(List<String> lines, String card) {
        return lines.stream().filter(line -> line.startsWith("data:") && line.contains(card)).toList();
    }
}
