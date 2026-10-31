package io.budget.watchdog.api;

import io.budget.watchdog.alerts.AlertRepository;
import io.budget.watchdog.evaluation.DetectionMetrics;
import io.budget.watchdog.evaluation.PipelineStats;
import io.budget.watchdog.evaluation.PipelineStats.Latency;
import io.budget.watchdog.evaluation.PipelineStats.Throughput;
import io.budget.watchdog.evaluation.RuleScore;
import io.budget.watchdog.ingest.TransactionRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/stats}: totals, detection quality per rule, and throughput and latency. Computed on every request.
 */
@RestController
class StatsController {

    /** Everything stored so far. */
    record Totals(long transactions, long alerts) {}

    record Stats(Totals totals, List<RuleScore> detectors, Throughput throughput, Latency latency) {}

    private final DetectionMetrics metrics;
    private final PipelineStats pipeline;
    private final TransactionRepository transactions;
    private final AlertRepository alerts;

    StatsController(DetectionMetrics metrics, PipelineStats pipeline, TransactionRepository transactions,
            AlertRepository alerts) {
        this.metrics = metrics;
        this.pipeline = pipeline;
        this.transactions = transactions;
        this.alerts = alerts;
    }

    @GetMapping("/api/stats")
    Stats stats() {
        return new Stats(new Totals(transactions.count(), alerts.count()), metrics.scores(), pipeline.throughput(),
                pipeline.latency());
    }
}
