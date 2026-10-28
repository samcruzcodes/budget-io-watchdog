package io.budget.watchdog.rules;

import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.util.Optional;

/**
 * One detection rule. Each rule is a Spring component, and the {@link RuleEngine} runs every rule on every new
 * transaction. A rule keeps its own per-card history in memory and only uses event time ({@code occurredAt}).
 */
public interface DetectionRule {

    /** A stable name, like "duplicate-charge". Phase 3 stores it with each alert as the detector's name. */
    String id();

    /** The one issue type this rule looks for. Its precision and recall are measured against this type. */
    IssueType detects();

    /**
     * Checks {@code tx} against what this rule knew about the card before, then learns from it. Called once per new
     * transaction, in order for each card.
     */
    Optional<RuleHit> evaluate(Transaction tx);

    /** A hit from this rule, with the explanation the cardholder would read. */
    default RuleHit hit(String explanation) {
        return new RuleHit(id(), detects(), explanation);
    }
}
