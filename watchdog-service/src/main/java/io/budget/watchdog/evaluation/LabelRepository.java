package io.budget.watchdog.evaluation;

import io.budget.watchdog.schema.GroundTruthLabel;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores ground-truth labels with plain SQL. */
@Repository
class LabelRepository {

    private final JdbcClient jdbc;

    LabelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertIfAbsent(GroundTruthLabel label, Instant receivedAt) {
        jdbc.sql("""
                        INSERT INTO ground_truth_labels (transaction_id, persona, issue_type, scenario_id, edge_case,
                                                         received_at)
                        VALUES (:transactionId, :persona, :issueType, :scenarioId, :edgeCase, :receivedAt)
                        ON CONFLICT (transaction_id) DO NOTHING
                        """)
                .param("transactionId", label.transactionId())
                .param("persona", label.persona())
                .param("issueType", label.issueType().name())
                .param("scenarioId", label.scenarioId())
                .param("edgeCase", label.edgeCase() == null ? null : label.edgeCase().name())
                .param("receivedAt", receivedAt.atOffset(ZoneOffset.UTC))
                .update();
    }
}
