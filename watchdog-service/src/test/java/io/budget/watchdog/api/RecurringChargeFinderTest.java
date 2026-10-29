package io.budget.watchdog.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.api.RecurringChargeFinder.RecurringCharge;
import io.budget.watchdog.api.RecurringChargeFinder.Status;
import io.budget.watchdog.rules.MonthlyPattern;
import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecurringChargeFinderTest {

    private static final Instant JAN_5 = Instant.parse("2026-01-05T08:00:00Z");

    // The price-hike rule's starting thresholds: 26 to 35 days apart, within 1%.
    private final RecurringChargeFinder finder =
            new RecurringChargeFinder(new MonthlyPattern(Duration.ofDays(26), Duration.ofDays(35), 1));

    private final List<Transaction> history = new ArrayList<>();

    private void charge(String merchant, Category category, long cents, int day) {
        Instant at = JAN_5.plus(Duration.ofDays(day));
        history.add(new Transaction(1, UUID.randomUUID(), "SIMULATOR", "user-0001", "card-0001", merchant, category,
                cents, "USD", "US", at, at));
    }

    private List<RecurringCharge> found(int nowDay) {
        return finder.find(history, JAN_5.plus(Duration.ofDays(nowDay)));
    }

    @Test
    void aSteadyMonthlyChargeIsActiveWithItsNextChargeAndYearlyCost() {
        charge("Netflix", Category.SUBSCRIPTIONS, 1_549, 0);
        charge("Netflix", Category.SUBSCRIPTIONS, 1_549, 31);

        assertThat(found(40)).singleElement().satisfies(netflix -> {
            assertThat(netflix.merchant()).isEqualTo("Netflix");
            assertThat(netflix.category()).isEqualTo(Category.SUBSCRIPTIONS);
            assertThat(netflix.status()).isEqualTo(Status.ACTIVE);
            assertThat(netflix.priceCents()).isEqualTo(1_549);
            assertThat(netflix.previousPriceCents()).isEqualTo(1_549);
            assertThat(netflix.lastChargedAt()).isEqualTo(JAN_5.plus(Duration.ofDays(31)));
            assertThat(netflix.nextExpectedAt()).isEqualTo(JAN_5.plus(Duration.ofDays(62)));
            assertThat(netflix.yearlyCostCents()).isEqualTo(18_588);
        });
    }

    @Test
    void aHikeAfterTwoSteadyMonthsIsPriceUp() {
        charge("Spotify", Category.SUBSCRIPTIONS, 1_199, 0);
        charge("Spotify", Category.SUBSCRIPTIONS, 1_199, 30);
        charge("Spotify", Category.SUBSCRIPTIONS, 1_399, 60);

        assertThat(found(61)).singleElement().satisfies(spotify -> {
            assertThat(spotify.status()).isEqualTo(Status.PRICE_UP);
            assertThat(spotify.priceCents()).isEqualTo(1_399);
            assertThat(spotify.previousPriceCents()).isEqualTo(1_199);
        });
    }

    @Test
    void rentAndBillsCountTooNotOnlySubscriptions() {
        charge("Maple Street Apartments", Category.RENT, 145_000, 0);
        charge("Maple Street Apartments", Category.RENT, 145_000, 31);

        assertThat(found(32)).extracting(RecurringCharge::category).containsExactly(Category.RENT);
    }

    @Test
    void aBillThatChangesEveryMonthIsNotListed() {
        charge("City Electric", Category.UTILITIES, 8_412, 0);
        charge("City Electric", Category.UTILITIES, 9_377, 31);
        charge("City Electric", Category.UTILITIES, 7_950, 59);

        assertThat(found(60)).isEmpty();
    }

    @Test
    void aSecondHikeInARowDropsOffLikeThePriceHikeRuleMisses() {
        charge("Apple iCloud+", Category.SUBSCRIPTIONS, 299, 0);
        charge("Apple iCloud+", Category.SUBSCRIPTIONS, 299, 30);
        charge("Apple iCloud+", Category.SUBSCRIPTIONS, 399, 60);
        charge("Apple iCloud+", Category.SUBSCRIPTIONS, 499, 90);

        // The months before the latest charge aren't steady, so it isn't PRICE_UP by the rule's definition.
        assertThat(found(91)).isEmpty();
    }

    @Test
    void aBillThatGoesUpThisMonthIsStillNotAPriceHike() {
        charge("City Power & Light", Category.UTILITIES, 11_955, 0);
        charge("City Power & Light", Category.UTILITIES, 10_412, 31);
        charge("City Power & Light", Category.UTILITIES, 13_002, 59);

        assertThat(found(60)).isEmpty();
    }

    @Test
    void aWeeklyMerchantIsNotMonthly() {
        for (int week = 0; week < 6; week++) {
            charge("Corner Market", Category.GROCERIES, 4_200, week * 7);
        }

        assertThat(found(36)).isEmpty();
    }

    @Test
    void oneChargeIsNotAPattern() {
        charge("Hulu", Category.SUBSCRIPTIONS, 799, 0);

        assertThat(found(5)).isEmpty();
    }

    @Test
    void noChargeForMoreThan35DaysIsOverdue() {
        charge("Netflix", Category.SUBSCRIPTIONS, 1_549, 0);
        charge("Netflix", Category.SUBSCRIPTIONS, 1_549, 31);

        assertThat(found(31 + 35)).extracting(RecurringCharge::status).containsExactly(Status.ACTIVE);
        assertThat(found(31 + 36)).extracting(RecurringCharge::status).containsExactly(Status.OVERDUE);
    }

    @Test
    void incomeIsNotARecurringCharge() {
        charge("Northwind Payroll", Category.INCOME, 420_000, 0);
        charge("Northwind Payroll", Category.INCOME, 420_000, 31);

        assertThat(found(32)).isEmpty();
    }

    @Test
    void sortedByMerchant() {
        charge("Spotify", Category.SUBSCRIPTIONS, 1_199, 0);
        charge("Spotify", Category.SUBSCRIPTIONS, 1_199, 30);
        charge("Netflix", Category.SUBSCRIPTIONS, 1_549, 1);
        charge("Netflix", Category.SUBSCRIPTIONS, 1_549, 31);

        assertThat(found(32)).extracting(RecurringCharge::merchant).containsExactly("Netflix", "Spotify");
    }

    @Test
    void looksBackThreeMonthlyGaps() {
        assertThat(finder.lookback()).isEqualTo(Duration.ofDays(105));
    }
}
