package io.budget.watchdog.api;

import io.budget.watchdog.schema.Category;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A month of a user's money, grouped the way the Budget.io planner groups it: income, then one total per budget line
 * (Food is groceries plus dining). Every line is listed, zeros included, in a fixed order, so the planner can rely on
 * the shape. Cents, like everywhere else.
 */
record SpendingSummary(String userId, String month, long incomeCents, long spendingCents, List<Line> lines) {

    record Line(String line, long cents) {}

    static SpendingSummary of(String userId, YearMonth month, Map<Category, Long> totalsByCategory) {
        Map<String, Long> byLine = new LinkedHashMap<>();
        for (Category category : Category.values()) { // enum order gives the lines a fixed order
            if (category != Category.INCOME) {
                byLine.merge(category.budgetIoLine(), totalsByCategory.getOrDefault(category, 0L), Long::sum);
            }
        }
        List<Line> lines = new ArrayList<>();
        byLine.forEach((line, cents) -> lines.add(new Line(line, cents)));
        long spending = lines.stream().mapToLong(Line::cents).sum();
        return new SpendingSummary(userId, month.toString(), totalsByCategory.getOrDefault(Category.INCOME, 0L),
                spending, lines);
    }
}
