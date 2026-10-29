package io.budget.watchdog.evaluation;

import io.budget.watchdog.schema.IssueType;

/**
 * How well one detector did against the labels. Precision counts alerts; recall counts scenarios (one injected
 * incident, like a whole card-testing attack), so a caught attack counts once however many of its charges fired.
 *
 * @param truePositives   alerts on transactions labeled with the detector's issue type
 * @param falsePositives  alerts on transactions labeled with anything else (look-alikes are labeled NONE)
 * @param unlabeledAlerts alerts whose label hasn't arrived, or never will (real bank data has no labels); not scored
 * @param scenarios       injected scenarios of this issue type whose transactions are stored
 * @param caughtScenarios scenarios with at least one true positive alert
 * @param falseNegatives  scenarios with no true positive alert
 * @param precision       true / (true + false) positives, or null with no labeled alerts yet
 * @param recall          caught / all scenarios, or null with no scenarios yet
 */
public record RuleScore(
        String detector,
        IssueType issueType,
        long alerts,
        long truePositives,
        long falsePositives,
        long unlabeledAlerts,
        long scenarios,
        long caughtScenarios,
        long falseNegatives,
        Double precision,
        Double recall) {

    static RuleScore of(String detector, IssueType issueType, long truePositives, long falsePositives,
            long unlabeledAlerts, long scenarios, long caughtScenarios) {
        return new RuleScore(detector, issueType, truePositives + falsePositives + unlabeledAlerts, truePositives,
                falsePositives, unlabeledAlerts, scenarios, caughtScenarios, scenarios - caughtScenarios,
                ratio(truePositives, truePositives + falsePositives), ratio(caughtScenarios, scenarios));
    }

    /** null rather than 0 when there's nothing to divide: "no data yet" isn't "0%". */
    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
