package io.budget.watchdog.rules;

import static io.budget.watchdog.rules.TestTransactions.START;
import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PriceHikeRuleTest {

    private final PriceHikeRule rule =
            new PriceHikeRule(new PriceHikeRule.Settings(Duration.ofDays(26), Duration.ofDays(35), 1));

    @Test
    void aSteadyMonthlyPriceThatGoesUpFires() {
        charge("Netflix", 1_549, day(0));
        charge("Netflix", 1_549, day(30));

        Optional<RuleHit> hit = charge("Netflix", 1_799, day(60));

        assertThat(hit).hasValueSatisfying(hike -> {
            assertThat(hike.ruleId()).isEqualTo("price-hike");
            assertThat(hike.issueType()).isEqualTo(IssueType.PRICE_HIKE);
            assertThat(hike.explanation()).isEqualTo("Netflix charged $17.99, up from $15.49 last month.");
        });
    }

    @Test
    void theSamePriceDoesntFire() {
        assertThat(monthly("Spotify", 1_199, 1_199, 1_199)).isEmpty();
    }

    @Test
    void aPriceDropDoesntFire() {
        assertThat(monthly("Spotify", 1_199, 1_199, 999)).isEmpty();
    }

    @Test
    void aHikeOnTheSecondChargeEverIsMissed() {
        charge("Hulu", 999, day(0));

        assertThat(charge("Hulu", 1_199, day(30))).isEmpty(); // a known miss: there's no steady price to compare yet
    }

    @Test
    void aWeeklyMerchantDoesntFire() {
        charge("Trader Joe's", 4_500, day(0));
        charge("Trader Joe's", 4_500, day(7));

        assertThat(charge("Trader Joe's", 6_000, day(14))).isEmpty();
    }

    @Test
    void gapsOfExactly26And35DaysStillCountAsMonthly() {
        charge("Disney+", 1_399, day(0));
        charge("Disney+", 1_399, day(26));

        assertThat(charge("Disney+", 1_599, day(26 + 35))).isPresent();
    }

    @Test
    void aGapOneSecondUnder26DaysIsntMonthly() {
        charge("Disney+", 1_399, day(0));
        charge("Disney+", 1_399, day(26).minusSeconds(1));

        assertThat(charge("Disney+", 1_599, day(56))).isEmpty();
    }

    @Test
    void aGapOneSecondOver35DaysIsntMonthly() {
        charge("Disney+", 1_399, day(0));
        charge("Disney+", 1_399, day(30));

        assertThat(charge("Disney+", 1_599, day(30 + 35).plusSeconds(1))).isEmpty();
    }

    @Test
    void earlierChargesExactlyOnePercentApartStillCountAsSteady() {
        assertThat(monthly("Gym A", 10_000, 10_100, 10_202)).isPresent();
        assertThat(monthly("Gym B", 10_000, 10_101, 11_000)).isEmpty();
    }

    @Test
    void aRiseOfOnlyOnePercentIsntAHike() {
        assertThat(monthly("Gym A", 10_000, 10_000, 10_100)).isEmpty();
        assertThat(monthly("Gym B", 10_000, 10_000, 10_101)).isPresent();
    }

    @Test
    void theMonthsAfterAHikeDontFireAgain() {
        assertThat(monthly("Netflix", 1_549, 1_549, 1_799)).isPresent();

        assertThat(charge("Netflix", 1_799, day(90))).isEmpty();
        assertThat(charge("Netflix", 1_799, day(120))).isEmpty();
    }

    @Test
    void aSecondHikeTheVeryNextMonthIsMissed() {
        monthly("Netflix", 1_549, 1_549, 1_799);

        // A known miss: $15.49 and $17.99 aren't a steady price, so there's nothing to compare against.
        assertThat(charge("Netflix", 1_999, day(90))).isEmpty();
    }

    @Test
    void anotherCardsChargesDontCount() {
        charge("Netflix", 1_549, day(0));
        charge("Netflix", 1_549, day(30));

        assertThat(rule.evaluate(TestTransactions.charge().card("card-0002").merchant("Netflix")
                .category(Category.SUBSCRIPTIONS).cents(1_799).at(day(60)).build())).isEmpty();
    }

    // Look-alike from the simulator

    @Test
    void aUtilityBillThatVariesDoesntFire() {
        long[] bills = {8_500, 9_230, 7_810, 10_120, 8_890, 9_640};
        for (int month = 0; month < bills.length; month++) {
            assertThat(utility(bills[month], day(30 * month))).isEmpty();
        }
    }

    @Test
    void aUtilityBillThatRepeatsWithinOnePercentByChanceFiresAsAnExpectedFalsePositive() {
        utility(8_500, day(0));
        utility(8_540, day(30));

        assertThat(utility(9_230, day(60))).isPresent();
    }

    // Memory

    @Test
    void forgetsMerchantsQuietForMoreThan35Days() {
        int mostHeld = 0;
        for (int d = 0; d < 365; d++) {
            charge("Shop " + d, 2_500, day(d)); // a different merchant every day
            mostHeld = Math.max(mostHeld, rule.merchantsHeldFor("card-0001"));
        }

        // One a day over a 35-day window, both ends included.
        assertThat(mostHeld).isEqualTo(36);
    }

    /** Three charges at one merchant, 30 days apart. Returns what the third one did. */
    private Optional<RuleHit> monthly(String merchant, long first, long second, long third) {
        charge(merchant, first, day(0));
        charge(merchant, second, day(30));
        return charge(merchant, third, day(60));
    }

    private Optional<RuleHit> charge(String merchant, long cents, Instant at) {
        return rule.evaluate(TestTransactions.charge()
                .merchant(merchant).category(Category.SUBSCRIPTIONS).cents(cents).at(at).build());
    }

    private Optional<RuleHit> utility(long cents, Instant at) {
        return rule.evaluate(TestTransactions.charge()
                .merchant("City Power & Light").category(Category.UTILITIES).cents(cents).at(at).build());
    }

    private static Instant day(long day) {
        return START.plus(Duration.ofDays(day));
    }
}
