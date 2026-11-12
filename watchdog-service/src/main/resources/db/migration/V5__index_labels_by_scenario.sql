-- The Scenarios tab looks a scenario's transactions up by its ID (GET /api/scenarios/{scenarioId}), several times a
-- second while a run is in flight. The existing index starts with issue_type, so it can't serve that lookup.
CREATE INDEX ground_truth_labels_scenario_idx ON ground_truth_labels (scenario_id);
