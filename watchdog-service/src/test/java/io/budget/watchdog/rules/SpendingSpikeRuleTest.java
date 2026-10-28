package io.budget.watchdog.rules;

import static io.budget.watchdog.rules.TestTransactions.START;
import static io.budget.watchdog.schema.Category.DINING;
import static io.budget.watchdog.schema.Category.GROCERIES;
import static io.budget.watchdog.schema.Category.SHOPPING;
import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpendingSpikeRuleTest {

    /** 17 days with a mean of exactly $30.00 and a sample standard deviation of exactly $10.00: the line is $60.00. */
    private static final long[] USUAL_DAYS = {
            2_000, 4_000, 2_000, 4_000, 2_000, 4_000, 2_000, 4_000, 3_000,
            2_000, 4_000, 2_000, 4_000, 2_000, 4_000, 2_000, 4_000};

    private static final Instant MIDNIGHT = START.truncatedTo(ChronoUnit.DAYS);

    private final SpendingSpikeRule rule =
            new SpendingSpikeRule(new SpendingSpikeRule.Settings(Set.of(DINING, SHOPPING), 3.0, 14, 5_000));

    @Test
    void aDayFarAboveTheUsualFiresOnThePurchaseThatCrossesTheLine() {
        int today = pastDays(DINING, USUAL_DAYS);
        assertThat(spend(DINING, 4_500, at(today, 12))).isEmpty(); // $45 so far

        Optional<RuleHit> hit = spend(DINING, 13_740, at(today, 19)); // $182.40 so far

        assertThat(hit).hasValueSatisfying(spike -> {
            assertThat(spike.ruleId()).isEqualTo("spending-spike");
            assertThat(spike.issueType()).isEqualTo(IssueType.SPENDING_SPIKE);
            assertThat(spike.explanation()).isEqualTo(
                    "You've spent $182.40 on dining today. On a usual dining day this card spends about $30.00.");
        });
    }

    @Test
    void oneCentUnderTheLineDoesntFire() {
        int today = pastDays(DINING, USUAL_DAYS);

        assertThat(spend(DINING, 5_999, at(today, 12))).isEmpty();
    }

    @Test
    void oneCentOverTheLineFires() {
        int today = pastDays(DINING, USUAL_DAYS);

        assertThat(spend(DINING, 6_001, at(today, 12))).isPresent();
    }

    @Test
    void firesOncePerCategoryPerDay() {
        int today = pastDays(DINING, USUAL_DAYS);
        assertThat(spend(DINING, 10_000, at(today, 12))).isPresent();

        assertThat(spend(DINING, 5_000, at(today, 20))).isEmpty();
    }

    @Test
    void diningAndShoppingCanEachFireTheSameDay() {
        pastDays(DINING, USUAL_DAYS);
        int today = pastDays(SHOPPING, USUAL_DAYS);

        assertThat(spend(DINING, 10_000, at(today, 12))).isPresent();
        assertThat(spend(SHOPPING, 10_000, at(today, 15))).isPresent();
    }

    @Test
    void theNextDayStartsOver() {
        int today = pastDays(DINING, USUAL_DAYS);
        spend(DINING, 10_000, at(today, 12));

        assertThat(spend(DINING, 3_500, at(today + 1, 12))).isEmpty();
    }

    @Test
    void aSpikeDayJoinsTheBaselineLikeAnyOtherDay() {
        int today = pastDays(DINING, USUAL_DAYS);
        assertThat(spend(DINING, 20_000, at(today, 12))).isPresent();

        // $65 would cross the old $60 line, but the $200 day widened the baseline (decision 9: learn every day).
        assertThat(spend(DINING, 6_500, at(today + 1, 12))).isEmpty();
    }

    @Test
    void thirteenDaysOfHistoryArentEnough() {
        int today = pastDays(DINING, Arrays.copyOf(USUAL_DAYS, 13));

        assertThat(spend(DINING, 50_000, at(today, 12))).isEmpty();
    }

    @Test
    void fourteenDaysOfHistoryAreEnough() {
        int today = pastDays(DINING, Arrays.copyOf(USUAL_DAYS, 14));

        assertThat(spend(DINING, 50_000, at(today, 12))).isPresent();
    }

    @Test
    void aFlatHistoryStaysQuiet() {
        long[] sameCoffeeEveryDay = new long[20];
        Arrays.fill(sameCoffeeEveryDay, 545);
        int today = pastDays(DINING, sameCoffeeEveryDay);

        assertThat(spend(DINING, 10_000, at(today, 12))).isEmpty();
    }

    @Test
    void anUnusualDayUnderFiftyDollarsStaysQuietAndFiftyFires() {
        long[] smallDays = {500, 700, 500, 700, 500, 700, 500, 700, 500, 700, 500, 700, 500, 700, 500, 700};
        int today = pastDays("card-0001", DINING, smallDays);
        pastDays("card-0002", DINING, smallDays);

        assertThat(spend("card-0001", DINING, 4_999, at(today, 12))).isEmpty();
        assertThat(spend("card-0002", DINING, 5_000, at(today, 12))).isPresent();
    }

    @Test
    void anUnwatchedCategoryStaysQuiet() {
        int today = pastDays(GROCERIES, USUAL_DAYS);

        assertThat(spend(GROCERIES, 50_000, at(today, 12))).isEmpty();
    }

    @Test
    void daysWithNoSpendingArentPartOfTheUsualDay() {
        // A once-a-week shopper: 14 shopping days, 7 days apart, alternating $30 and $40.
        for (int week = 0; week < 14; week++) {
            assertThat(spend(SHOPPING, week % 2 == 0 ? 3_000 : 4_000, at(7 * week, 12))).isEmpty();
        }

        assertThat(spend(SHOPPING, 12_000, at(98, 12))).map(RuleHit::explanation)
                .hasValueSatisfying(text -> assertThat(text).endsWith("spends about $35.00.")); // not about $5
    }

    @Test
    void aPurchaseAfterMidnightUtcStartsANewDay() {
        int today = pastDays(DINING, USUAL_DAYS);

        assertThat(spend(DINING, 4_000, at(today, 23).plus(Duration.ofMinutes(30)))).isEmpty();
        assertThat(spend(DINING, 4_000, at(today + 1, 0).plus(Duration.ofMinutes(10)))).isEmpty(); // not $80 today
    }

    @Test
    void anotherCardsHistoryDoesntCount() {
        int today = pastDays(DINING, USUAL_DAYS);

        assertThat(spend("card-0002", DINING, 50_000, at(today, 12))).isEmpty();
    }

    // Look-alike from the simulator

    @Test
    void aPlannedBigPurchaseFiresAsAnExpectedFalsePositive() {
        int today = pastDays(SHOPPING, USUAL_DAYS);

        assertThat(rule.evaluate(TestTransactions.charge().merchant("Apple Store").category(SHOPPING)
                .cents(129_900).at(at(today, 14)).build())).isPresent();
    }

    /** One purchase a day at noon, from day 0, for card-0001. Returns the day after the last one. */
    private int pastDays(Category category, long... totals) {
        return pastDays("card-0001", category, totals);
    }

    private int pastDays(String cardId, Category category, long... totals) {
        for (int day = 0; day < totals.length; day++) {
            assertThat(spend(cardId, category, totals[day], at(day, 12))).isEmpty();
        }
        return totals.length;
    }

    private Optional<RuleHit> spend(Category category, long cents, Instant at) {
        return spend("card-0001", category, cents, at);
    }

    private Optional<RuleHit> spend(String cardId, Category category, long cents, Instant at) {
        return rule.evaluate(TestTransactions.charge().card(cardId).category(category).cents(cents).at(at).build());
    }

    private static Instant at(int day, int hour) {
        return MIDNIGHT.plus(Duration.ofDays(day)).plus(Duration.ofHours(hour));
    }
}
