package io.budget.watchdog.schema;

/**
 * What a simulated transaction really is, according to the simulator's answer key.
 * NONE for normal transactions, including legitimate look-alikes (see {@link EdgeCase}).
 */
public enum IssueType {
    NONE,
    DUPLICATE_CHARGE,
    TRIAL_CONVERSION,
    PRICE_HIKE,
    SPENDING_SPIKE,
    CARD_TESTING,
    NEW_COUNTRY
}
