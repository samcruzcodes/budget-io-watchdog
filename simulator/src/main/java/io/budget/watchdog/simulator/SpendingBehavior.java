package io.budget.watchdog.simulator;

/**
 * One area of a simulated person's financial life. Each simulated day, every behavior adds that day's transactions
 * to the user's plan. Adding a kind of spending means adding one class.
 */
interface SpendingBehavior {

    void planDay(DayPlan plan);
}
