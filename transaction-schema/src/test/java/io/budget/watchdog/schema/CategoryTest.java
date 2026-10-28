package io.budget.watchdog.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The Budget.io planner will read Watchdog's spending by these line names, so they are part of the contract. */
class CategoryTest {

    @ParameterizedTest
    @EnumSource(Category.class)
    void everyCategoryRollsUpIntoABudgetIoLine(Category category) {
        assertThat(category.budgetIoLine()).isNotBlank();
    }

    @Test
    void groceriesAndDiningBothCountAsFoodInBudgetIo() {
        assertThat(Category.GROCERIES.budgetIoLine()).isEqualTo("Food");
        assertThat(Category.DINING.budgetIoLine()).isEqualTo("Food");
    }

    @Test
    void budgetIoDefaultLinesKeepTheirExactNames() {
        assertThat(Category.RENT.budgetIoLine()).isEqualTo("Rent/Mortgage");
        assertThat(Category.UTILITIES.budgetIoLine()).isEqualTo("Utilities");
        assertThat(Category.TRANSPORTATION.budgetIoLine()).isEqualTo("Transportation");
        assertThat(Category.SHOPPING.budgetIoLine()).isEqualTo("TP Fund");
    }
}
