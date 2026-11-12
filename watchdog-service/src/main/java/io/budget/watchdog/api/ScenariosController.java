package io.budget.watchdog.api;

import io.budget.watchdog.evaluation.ScenarioResults;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/scenarios/{scenarioId}}: what's stored for one scenario, and what the rules said about it. The
 * dashboard's Scenarios tab asks the simulator to publish a scenario, then polls this until every transaction it was
 * told about has been stored. Read-only: nothing in the watchdog can create a transaction or an alert over HTTP.
 */
@RestController
class ScenariosController {

    private final ScenarioResults results;

    ScenariosController(ScenarioResults results) {
        this.results = results;
    }

    /** An ID that isn't a UUID is a 400; one nothing is stored for yet is an empty result, not an error. */
    @GetMapping("/api/scenarios/{scenarioId}")
    ScenarioResults.Result scenario(@PathVariable UUID scenarioId) {
        return results.find(scenarioId);
    }
}
