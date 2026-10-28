package io.budget.watchdog.schema;

/**
 * Spending categories shared by every source. Each one rolls up into exactly one line of the
 * Budget.io planner, so the planner can read Watchdog's data later (see the Budget.io section of docs/DESIGN.md).
 */
public enum Category {
    INCOME("income"),
    RENT("Rent/Mortgage"),
    UTILITIES("Utilities"),
    GROCERIES("Food"),
    DINING("Food"),
    TRANSPORTATION("Transportation"),
    SUBSCRIPTIONS("Subscriptions"),
    SHOPPING("TP Fund"),
    TRAVEL("Travel");

    private final String budgetIoLine;

    Category(String budgetIoLine) {
        this.budgetIoLine = budgetIoLine;
    }

    /** The Budget.io budget line this category rolls up into; "income" means Budget.io's income field. */
    public String budgetIoLine() {
        return budgetIoLine;
    }
}
