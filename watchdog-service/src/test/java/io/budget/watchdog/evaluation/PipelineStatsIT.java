package io.budget.watchdog.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.budget.watchdog.IntegrationTestContainers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Throughput and latency against real Postgres. Each test fixes "now" years ahead of real time, so only its own rows
 * fall inside the windows, whatever else other tests stored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class PipelineStatsIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.register(registry);
    }

    @Autowired
    JdbcClient jdbc;

    @Test
    void throughputAndLatencyPercentilesOverTheirWindows() {
        Instant now = Instant.parse("2035-05-05T12:00:00Z");
        // Ten stored in the last minute, 10 ms to 100 ms after they were published.
        for (int i = 1; i <= 10; i++) {
            stored(now.minusSeconds(10), Duration.ofMillis(10L * i));
        }
        stored(now.minus(Duration.ofMinutes(2)), Duration.ofMillis(1_000));  // in the latency window only
        stored(now.minus(Duration.ofMinutes(10)), Duration.ofMillis(5_000)); // in neither window
        PipelineStats stats = new PipelineStats(jdbc, Clock.fixed(now, ZoneOffset.UTC));

        PipelineStats.Throughput throughput = stats.throughput();
        assertThat(throughput.windowSeconds()).isEqualTo(60);
        assertThat(throughput.transactions()).isEqualTo(10);
        assertThat(throughput.perSecond()).isCloseTo(10 / 60.0, within(1e-9));

        // Eleven values: 10, 20, ..., 100, then 1000. percentile_cont interpolates between neighbors, like numpy's
        // default: p50 is the 6th value; p95 sits halfway from 100 to 1000; p99 is 90% of the way.
        PipelineStats.Latency latency = stats.latency();
        assertThat(latency.windowSeconds()).isEqualTo(300);
        assertThat(latency.samples()).isEqualTo(11);
        assertThat(latency.p50Ms()).isCloseTo(60.0, within(1e-6));
        assertThat(latency.p95Ms()).isCloseTo(550.0, within(1e-6));
        assertThat(latency.p99Ms()).isCloseTo(910.0, within(1e-6));
    }

    @Test
    void anEmptyWindowGivesZeroThroughputAndNoPercentiles() {
        PipelineStats stats = new PipelineStats(jdbc, Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"), ZoneOffset.UTC));

        assertThat(stats.throughput().transactions()).isZero();
        assertThat(stats.throughput().perSecond()).isZero();
        PipelineStats.Latency latency = stats.latency();
        assertThat(latency.samples()).isZero();
        assertThat(latency.p50Ms()).isNull();
        assertThat(latency.p95Ms()).isNull();
        assertThat(latency.p99Ms()).isNull();
    }

    private void stored(Instant processedAt, Duration latency) {
        jdbc.sql("""
                        INSERT INTO transactions (transaction_id, source, user_id, card_id, merchant, category,
                                                  amount_cents, currency, country, occurred_at, published_at,
                                                  processed_at)
                        VALUES (:id, 'SIMULATOR', 'user-0001', :card, 'Blue Bottle Coffee', 'DINING', 545, 'USD',
                                'US', :occurredAt, :publishedAt, :processedAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("card", "card-" + UUID.randomUUID())
                .param("occurredAt", processedAt.atOffset(ZoneOffset.UTC))
                .param("publishedAt", processedAt.minus(latency).atOffset(ZoneOffset.UTC))
                .param("processedAt", processedAt.atOffset(ZoneOffset.UTC))
                .update();
    }
}
