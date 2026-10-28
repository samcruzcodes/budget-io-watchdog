package io.budget.watchdog.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void formatsCentsAsDollars() {
        assertThat(Money.format(0)).isEqualTo("$0.00");
        assertThat(Money.format(99)).isEqualTo("$0.99");
        assertThat(Money.format(1_549)).isEqualTo("$15.49");
        assertThat(Money.format(129_900)).isEqualTo("$1,299.00");
    }
}
