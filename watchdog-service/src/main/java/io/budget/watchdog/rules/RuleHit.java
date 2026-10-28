package io.budget.watchdog.rules;

import io.budget.watchdog.schema.IssueType;

/**
 * A rule fired on a transaction.
 *
 * @param explanation plain English for the cardholder, including the numbers behind the hit
 */
public record RuleHit(String ruleId, IssueType issueType, String explanation) {

    public RuleHit {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId is required");
        }
        if (issueType == null || issueType == IssueType.NONE) {
            throw new IllegalArgumentException("a hit needs the issue type its rule detects, not " + issueType);
        }
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("explanation is required");
        }
    }
}
