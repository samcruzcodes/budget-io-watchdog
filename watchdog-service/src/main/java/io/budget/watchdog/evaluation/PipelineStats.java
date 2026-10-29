package io.budget.watchdog.evaluation;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How fast transactions get through, from the real-clock timestamps: {@code published_at} (the source sent it) and
 * {@code processed_at} (the watchdog stored it). Latency assumes the source and the watchdog share a clock, which is
 * true when they run on one machine.
 */
@Component
public class PipelineStats {

    static final Duration THROUGHPUT_WINDOW = Duration.ofSeconds(60);
    static final Duration LATENCY_WINDOW = Duration.ofMinutes(5);

    /** Transactions stored per second over the last minute. */
    public record Throughput(long windowSeconds, long transactions, double perSecond) {}

    /** Publish-to-stored latency over the last 5 minutes, in milliseconds; null percentiles when nothing was stored. */
    public record Latency(long windowSeconds, long samples, Double p50Ms, Double p95Ms, Double p99Ms) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    PipelineStats(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Throughput throughput() {
        long stored = jdbc.sql("SELECT count(*) FROM transactions WHERE processed_at > :since")
                .param("since", since(THROUGHPUT_WINDOW))
                .query(Long.class)
                .single();
        return new Throughput(THROUGHPUT_WINDOW.toSeconds(), stored, (double) stored / THROUGHPUT_WINDOW.toSeconds());
    }

    public Latency latency() {
        return jdbc.sql("""
                        SELECT count(*) AS samples,
                               percentile_cont(0.50) WITHIN GROUP (ORDER BY latency_ms) AS p50,
                               percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95,
                               percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms) AS p99
                        FROM (SELECT extract(epoch FROM processed_at - published_at) * 1000 AS latency_ms
                              FROM transactions
                              WHERE processed_at > :since) recent
                        """)
                .param("since", since(LATENCY_WINDOW))
                .query((row, n) -> new Latency(LATENCY_WINDOW.toSeconds(), row.getLong("samples"),
                        row.getObject("p50", Double.class), row.getObject("p95", Double.class),
                        row.getObject("p99", Double.class)))
                .single();
    }

    /** The start of a window that ends now, on the real clock. */
    private OffsetDateTime since(Duration window) {
        return clock.instant().minus(window).atOffset(ZoneOffset.UTC);
    }
}
