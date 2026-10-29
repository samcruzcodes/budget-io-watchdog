package io.budget.watchdog;

import static io.budget.watchdog.TestKafka.charge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.budget.watchdog.schema.Transaction;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A real restart: run the app, stop it, start it again. The second run only knows a card's history if the warm-up
 * replayed it from Postgres, so the hits it raises prove the warm-up ran, and ran before the listener.
 */
class WarmUpIT {

    // Far in the future, so these are the newest transactions in the shared test database and set the 90-day window.
    private static final Instant DAY = Instant.parse("2090-01-01T12:00:00Z");

    private final String subscriber = TestKafka.newCard();
    private final String traveler = TestKafka.newCard();

    @Test
    void aRestartedWatchdogRemembersTheLast90DaysOfHistory() throws Exception {
        List<Transaction> history = new ArrayList<>();
        // Two steady monthly charges: price-hike needs both to notice the third one is higher.
        history.add(charge(subscriber, "Netflix", "US", 1_549, DAY.minus(days(60))));
        history.add(charge(subscriber, "Netflix", "US", 1_549, DAY.minus(days(30))));
        // France, learned quietly while the card was new, but 120 days back: outside the warm-up's window.
        history.add(charge(traveler, "Café de Flore", "FR", 1_850, DAY.minus(days(120))));
        // 30 everyday charges at home, inside the window, so new-country is past its 20-transaction learning period.
        for (int day = 30; day >= 1; day--) {
            history.add(charge(traveler, "Corner Market", "US", 1_000, DAY.minus(days(day))));
        }

        try (ConfigurableApplicationContext first = start()) {
            TestKafka.send(history.toArray(Transaction[]::new));
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> storedFor(first, subscriber) + storedFor(first, traveler) == history.size());
            assertThat(alertsFor(first, subscriber)).isEmpty();
            assertThat(alertsFor(first, traveler)).isEmpty();
        }

        try (ConfigurableApplicationContext second = start()) {
            assertThat(health(second, "readiness")).contains("\"UP\"");
            assertThat(health(second, "liveness")).contains("\"UP\"");

            TestKafka.send(
                    charge(subscriber, "Netflix", "US", 1_849, DAY),
                    charge(traveler, "Luxury Goods Outlet", "FR", 81_240, DAY.plus(Duration.ofHours(1))));

            await().atMost(Duration.ofSeconds(30)).until(() -> alertsFor(second, subscriber).size()
                    + alertsFor(second, traveler).size() == 2);
            assertThat(alertsFor(second, subscriber)).containsExactly("price-hike");
            // Without the window France would still be known; without any warm-up the card would still be learning.
            assertThat(alertsFor(second, traveler)).containsExactly("new-country");
        }
    }

    private static ConfigurableApplicationContext start() {
        List<String> args = new ArrayList<>(List.of(IntegrationTestContainers.connectionProperties()));
        args.add("--server.port=0"); // any free port
        return new SpringApplicationBuilder(WatchdogApplication.class).run(args.toArray(String[]::new));
    }

    private static long storedFor(ConfigurableApplicationContext app, String card) {
        return app.getBean(JdbcClient.class).sql("SELECT count(*) FROM transactions WHERE card_id = :card")
                .param("card", card).query(Long.class).single();
    }

    private static List<String> alertsFor(ConfigurableApplicationContext app, String card) {
        return app.getBean(JdbcClient.class).sql("SELECT detector FROM alerts WHERE card_id = :card ORDER BY detector")
                .param("card", card).query(String.class).list();
    }

    private static String health(ConfigurableApplicationContext app, String probe)
            throws IOException, InterruptedException {
        String port = app.getEnvironment().getProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/health/" + probe)).build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    private static Duration days(int days) {
        return Duration.ofDays(days);
    }
}
