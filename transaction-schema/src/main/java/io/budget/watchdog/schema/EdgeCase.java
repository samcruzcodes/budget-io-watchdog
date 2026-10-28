package io.budget.watchdog.schema;

/**
 * Legitimate transactions that look like an issue (see Injected issues and look-alikes in docs/DESIGN.md).
 * They keep detection metrics honest: a rule that fires on one of these produces a false positive.
 */
public enum EdgeCase {
    /** Same coffee, same price, minutes apart (buying for a friend). Looks like a duplicate charge. */
    DOUBLE_COFFEE,
    /** A free trial cancelled before it converted to a paid plan. */
    CANCELLED_TRIAL,
    /** $0.99, then $9.99 at the App Store. Looks like a free trial converting. */
    APP_STORE_PURCHASES,
    /** A planned large purchase, such as a laptop. Looks like a spending spike. */
    LEGIT_BIG_PURCHASE,
    /** A trip abroad, booked in advance. Looks like new-country charges and spending spikes. */
    LEGIT_TRAVEL,
    /** Several small tolls, then a hotel. Looks like card testing. */
    TOLL_ROAD_TRIP,
    /** One order from a foreign online shop, with no trip. Looks like a new-country charge. */
    FOREIGN_ONLINE_ORDER
}
