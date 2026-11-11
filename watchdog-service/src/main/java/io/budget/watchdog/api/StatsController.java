package io.budget.watchdog.api;

import io.budget.watchdog.alerts.AlertRepository;
import io.budget.watchdog.evaluation.DetectionMetrics;
import io.budget.watchdog.evaluation.PipelineStats;
import io.budget.watchdog.evaluation.PipelineStats.Latency;
import io.budget.watchdog.evaluation.PipelineStats.Throughput;
import io.budget.watchdog.evaluation.RuleScore;
import io.budget.watchdog.ingest.SkippedDuplicates;
import io.budget.watchdog.ingest.TransactionRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/stats}: totals, detection quality per rule, throughput and latency, and how many re-sent messages
 * were skipped lately. Computed on every request.
 */
@RestController
class StatsController {

    /** Everything stored so far. */
    record Totals(long transactions, long alerts) {}

    /** {@code duplicates} is this watchdog's own count (see {@link SkippedDuplicates}); everything else is from Postgres. */
    record Stats(Totals totals, List<RuleScore> detectors, Throughput throughput, Latency latency,
            SkippedDuplicates.Recent duplicates) {}

    private final DetectionMetrics metrics;
    private final PipelineStats pipeline;
    private final TransactionRepository transactions;
    private final AlertRepository alerts;
    private final SkippedDuplicates skippedDuplicates;

    StatsController(DetectionMetrics metrics, PipelineStats pipeline, TransactionRepository transactions,
            AlertRepository alerts, SkippedDuplicates skippedDuplicates) {
        this.metrics = metrics;
        this.pipeline = pipeline;
        this.transactions = transactions;
        this.alerts = alerts;
        this.skippedDuplicates = skippedDuplicates;
    }

    @GetMapping("/api/stats")
    Stats stats() {
        return new Stats(new Totals(transactions.count(), alerts.count()), metrics.scores(), pipeline.throughput(),
                pipeline.latency(), skippedDuplicates.recent());
    }
}
