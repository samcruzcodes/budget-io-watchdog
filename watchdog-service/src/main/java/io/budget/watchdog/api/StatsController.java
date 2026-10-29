package io.budget.watchdog.api;

import io.budget.watchdog.evaluation.DetectionMetrics;
import io.budget.watchdog.evaluation.PipelineStats;
import io.budget.watchdog.evaluation.PipelineStats.Latency;
import io.budget.watchdog.evaluation.PipelineStats.Throughput;
import io.budget.watchdog.evaluation.RuleScore;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/stats}: detection quality per rule, plus throughput and latency. Computed on every request. */
@RestController
class StatsController {

    record Stats(List<RuleScore> detectors, Throughput throughput, Latency latency) {}

    private final DetectionMetrics metrics;
    private final PipelineStats pipeline;

    StatsController(DetectionMetrics metrics, PipelineStats pipeline) {
        this.metrics = metrics;
        this.pipeline = pipeline;
    }

    @GetMapping("/api/stats")
    Stats stats() {
        return new Stats(metrics.scores(), pipeline.throughput(), pipeline.latency());
    }
}
