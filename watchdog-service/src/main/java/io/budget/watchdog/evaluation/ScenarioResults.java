package io.budget.watchdog.evaluation;

import io.budget.watchdog.alerts.AlertRepository;
import io.budget.watchdog.ingest.TransactionRepository;
import io.budget.watchdog.schema.Alert;
import io.budget.watchdog.schema.Transaction;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the watchdog actually did with one scenario: the transactions it stored and the alerts its rules raised on
 * them. A scenario's transactions are tied together by the scenario ID in their ground-truth labels, so this lives
 * with the scoring code, the only code allowed to read labels. The rules never see the ID; they just saw the
 * transactions arrive from Kafka like any others.
 */
@Component
public class ScenarioResults {

    /** {@code transactions} holds only those whose label has arrived too, in the order they happened. */
    public record Result(UUID scenarioId, List<Transaction> transactions, List<Alert> alerts) {}

    private final JdbcClient jdbc;
    private final TransactionRepository transactions;
    private final AlertRepository alerts;

    ScenarioResults(JdbcClient jdbc, TransactionRepository transactions, AlertRepository alerts) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.alerts = alerts;
    }

    /** Empty lists for a scenario nothing is stored for (yet): the caller may be asking before the messages arrive. */
    public Result find(UUID scenarioId) {
        List<UUID> ids = jdbc.sql("SELECT transaction_id FROM ground_truth_labels WHERE scenario_id = :scenarioId")
                .param("scenarioId", scenarioId)
                .query(UUID.class)
                .list();
        return new Result(scenarioId, transactions.byIds(ids), alerts.forTransactions(ids));
    }
}
