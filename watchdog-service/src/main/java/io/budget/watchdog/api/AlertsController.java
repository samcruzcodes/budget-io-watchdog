package io.budget.watchdog.api;

import io.budget.watchdog.alerts.AlertRepository;
import io.budget.watchdog.rules.DetectionRule;
import io.budget.watchdog.schema.Alert;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/alerts}: the newest alerts with their explanations, by event time. */
@RestController
class AlertsController {

    private final AlertRepository alerts;
    private final Set<String> detectors;

    AlertsController(AlertRepository alerts, List<DetectionRule> rules) {
        this.alerts = alerts;
        this.detectors = rules.stream().map(DetectionRule::id).collect(Collectors.toSet());
    }

    @GetMapping("/api/alerts")
    List<Alert> recent(@RequestParam(required = false) String userId,
            @RequestParam(required = false) String detector,
            @RequestParam(defaultValue = "" + ApiParams.DEFAULT_LIMIT) int limit) {
        String onlyDetector = ApiParams.optional(detector);
        // A typo like "duplicate-charges" would otherwise quietly return nothing.
        if (onlyDetector != null && !detectors.contains(onlyDetector)) {
            throw ApiParams.badRequest("unknown detector " + onlyDetector + "; known: " + detectors.stream().sorted().toList());
        }
        return alerts.recent(ApiParams.optional(userId), onlyDetector, ApiParams.limit(limit));
    }
}
