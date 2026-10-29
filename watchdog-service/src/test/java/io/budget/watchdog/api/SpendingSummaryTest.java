package io.budget.watchdog.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.api.SpendingSummary.Line;
import io.budget.watchdog.schema.Category;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpendingSummaryTest {

    @Test
    void groupsCategoriesIntoBudgetIoLines() {
        SpendingSummary summary = SpendingSummary.of("user-0001", YearMonth.of(2026, 3), Map.of(
                Category.INCOME, 524_000L,
                Category.RENT, 145_000L,
                Category.GROCERIES, 31_250L,
                Category.DINING, 18_740L,
                Category.SHOPPING, 9_999L));

        assertThat(summary.userId()).isEqualTo("user-0001");
        assertThat(summary.month()).isEqualTo("2026-03");
        assertThat(summary.incomeCents()).isEqualTo(524_000);
        assertThat(summary.spendingCents()).isEqualTo(145_000 + 31_250 + 18_740 + 9_999);
        assertThat(summary.lines()).containsExactly(
                new Line("Rent/Mortgage", 145_000),
                new Line("Utilities", 0),
                new Line("Food", 31_250 + 18_740), // groceries and dining are both Food in Budget.io
                new Line("Transportation", 0),
                new Line("Subscriptions", 0),
                new Line("TP Fund", 9_999),
                new Line("Travel", 0));
    }

    @Test
    void aMonthWithNothingIsAllZeros() {
        SpendingSummary summary = SpendingSummary.of("user-0042", YearMonth.of(2026, 3), Map.of());

        assertThat(summary.incomeCents()).isZero();
        assertThat(summary.spendingCents()).isZero();
        assertThat(summary.lines()).hasSize(7).allSatisfy(line -> assertThat(line.cents()).isZero());
    }
}
