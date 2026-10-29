package io.budget.watchdog.evaluation;

import io.budget.watchdog.rules.DetectionRule;
import io.budget.watchdog.schema.IssueType;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Precision and recall per rule, computed on request by comparing the alerts with the labels in Postgres. It matches
 * the Python prototypes' counting except for missing labels: those alerts are reported as unlabeled instead of being
 * counted as false positives, and an empty ratio is null instead of 0.
 */
@Component
public class DetectionMetrics {

    private record AlertCounts(String detector, long truePositives, long falsePositives, long unlabeled, long caught) {}

    private record ScenarioCount(IssueType issueType, long scenarios) {}

    private final JdbcClient jdbc;
    private final List<DetectionRule> rules;

    DetectionMetrics(JdbcClient jdbc, List<DetectionRule> rules) {
        this.jdbc = jdbc;
        this.rules = rules.stream().sorted(Comparator.comparing(DetectionRule::id)).toList();
    }

    /** One score per rule, in rule ID order, including rules that haven't raised an alert yet. */
    public List<RuleScore> scores() {
        // Each alert next to its transaction's label, if the label has arrived.
        Map<String, AlertCounts> alerts = jdbc.sql("""
                        SELECT a.detector,
                               count(*) FILTER (WHERE l.issue_type = a.issue_type)                          AS tp,
                               count(*) FILTER (WHERE l.issue_type <> a.issue_type)                         AS fp,
                               count(*) FILTER (WHERE l.transaction_id IS NULL)                             AS unlabeled,
                               count(DISTINCT l.scenario_id) FILTER (WHERE l.issue_type = a.issue_type)     AS caught
                        FROM alerts a
                        LEFT JOIN ground_truth_labels l ON l.transaction_id = a.transaction_id
                        GROUP BY a.detector
                        """)
                .query((row, n) -> new AlertCounts(row.getString("detector"), row.getLong("tp"), row.getLong("fp"),
                        row.getLong("unlabeled"), row.getLong("caught")))
                .list().stream()
                .collect(Collectors.toMap(AlertCounts::detector, Function.identity()));

        // Injected scenarios per issue type, counting only transactions that are stored, so a label that arrived
        // before its transaction doesn't count as a miss yet.
        Map<IssueType, Long> scenarios = jdbc.sql("""
                        SELECT l.issue_type, count(DISTINCT l.scenario_id) AS scenarios
                        FROM ground_truth_labels l
                        JOIN transactions t ON t.transaction_id = l.transaction_id
                        WHERE l.issue_type <> 'NONE'
                        GROUP BY l.issue_type
                        """)
                .query((row, n) -> new ScenarioCount(IssueType.valueOf(row.getString("issue_type")),
                        row.getLong("scenarios")))
                .list().stream()
                .collect(Collectors.toMap(ScenarioCount::issueType, ScenarioCount::scenarios));

        return rules.stream().map(rule -> {
            AlertCounts counts = alerts.getOrDefault(rule.id(), new AlertCounts(rule.id(), 0, 0, 0, 0));
            return RuleScore.of(rule.id(), rule.detects(), counts.truePositives(), counts.falsePositives(),
                    counts.unlabeled(), scenarios.getOrDefault(rule.detects(), 0L), counts.caught());
        }).toList();
    }
}
