package io.budget.watchdog.rules;

import static io.budget.watchdog.rules.TestTransactions.START;
import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TrialConversionRuleTest {

    private static final Duration MIN_DELAY = Duration.ofDays(5);
    private static final Duration MAX_DELAY = Duration.ofDays(45);

    private final TrialConversionRule rule =
            new TrialConversionRule(new TrialConversionRule.Settings(100, 5, MIN_DELAY, MAX_DELAY));

    @Test
    void aFreeTrialThenFullPriceFires() {
        assertThat(charge("Peacock", 0, START)).isEmpty();

        Optional<RuleHit> hit = charge("Peacock", 1_099, START.plus(Duration.ofDays(12)));

        assertThat(hit).hasValueSatisfying(conversion -> {
            assertThat(conversion.ruleId()).isEqualTo("trial-conversion");
            assertThat(conversion.issueType()).isEqualTo(IssueType.TRIAL_CONVERSION);
            assertThat(conversion.explanation()).isEqualTo("Peacock charged $10.99, 12 days after a $0.00 charge. "
                    + "A free trial may have turned into a paid plan.");
        });
    }

    @Test
    void exactlyFiveTimesADollarSignupFires() {
        charge("Calm", 100, START);

        assertThat(charge("Calm", 500, START.plus(Duration.ofDays(10)))).isPresent();
    }

    @Test
    void justUnderFiveTimesDoesntFire() {
        charge("Calm", 100, START);

        assertThat(charge("Calm", 499, START.plus(Duration.ofDays(10)))).isEmpty();
    }

    @Test
    void afterAFreeSignupTheChargeMustBeMoreThanOneDollar() {
        charge("MasterClass", 0, START);

        assertThat(charge("MasterClass", 100, START.plus(Duration.ofDays(10)))).isEmpty();
    }

    @Test
    void exactlyFiveDaysLaterFires() {
        charge("Skillshare", 0, START);

        assertThat(charge("Skillshare", 1_399, START.plus(MIN_DELAY))).isPresent();
    }

    @Test
    void oneSecondSoonerThanFiveDaysDoesntFire() {
        charge("Skillshare", 0, START);

        assertThat(charge("Skillshare", 1_399, START.plus(MIN_DELAY).minusSeconds(1))).isEmpty();
    }

    @Test
    void exactlyFortyFiveDaysLaterFires() {
        charge("Skillshare", 0, START);

        assertThat(charge("Skillshare", 1_399, START.plus(MAX_DELAY))).isPresent();
    }

    @Test
    void oneSecondMoreThanFortyFiveDaysLaterDoesntFire() {
        charge("Skillshare", 0, START);

        assertThat(charge("Skillshare", 1_399, START.plus(MAX_DELAY).plusSeconds(1))).isEmpty();
    }

    @Test
    void aGasPumpHoldIsForgottenOnceTheRealChargeArrives() {
        assertThat(charge("Shell", 100, START)).isEmpty();                               // $1 hold
        assertThat(charge("Shell", 4_850, START.plus(Duration.ofMinutes(4)))).isEmpty(); // the fill-up

        assertThat(charge("Shell", 5_210, START.plus(Duration.ofDays(7)))).isEmpty();    // next week's fill-up
    }

    @Test
    void aFullPriceChargeInsideFiveDaysMeansTheSmallOneWasntATrial() {
        assertThat(charge("Calm", 100, START)).isEmpty();
        assertThat(charge("Calm", 1_499, START.plus(Duration.ofDays(3)))).isEmpty(); // too soon, and it forgets the $1

        assertThat(charge("Calm", 1_499, START.plus(Duration.ofDays(10)))).isEmpty();
    }

    @Test
    void firesOncePerTrialNotOnTheNextRenewal() {
        charge("Max", 0, START);
        assertThat(charge("Max", 1_699, START.plus(Duration.ofDays(10)))).isPresent();

        assertThat(charge("Max", 1_699, START.plus(Duration.ofDays(40)))).isEmpty();
    }

    @Test
    void aCancelledTrialNeverFiresEvenWhenOtherMerchantsCharge() {
        charge("Calm", 0, START);

        assertThat(charge("HelloFresh", 6_999, START.plus(Duration.ofDays(12)))).isEmpty();
    }

    @Test
    void aSignupOnAnotherCardDoesntCount() {
        charge("Peacock", 0, START);

        assertThat(rule.evaluate(TestTransactions.charge().card("card-0002").merchant("Peacock")
                .category(Category.SUBSCRIPTIONS).cents(1_099).at(START.plus(Duration.ofDays(12))).build())).isEmpty();
    }

    // Look-alike from the simulator

    @Test
    void appStorePurchasesFireAsAnExpectedFalsePositive() {
        rule.evaluate(TestTransactions.charge().merchant("Apple App Store").cents(99).at(START).build());

        assertThat(rule.evaluate(TestTransactions.charge().merchant("Apple App Store").cents(999)
                .at(START.plus(Duration.ofDays(10))).build())).isPresent();
    }

    // Memory

    @Test
    void holdsOnlySmallChargesFromTheLastFortyFiveDays() {
        int mostHeld = 0;
        for (int day = 0; day < 365; day++) {
            charge("Shop " + day, 99, START.plus(Duration.ofDays(day))); // a different merchant every day
            mostHeld = Math.max(mostHeld, rule.smallChargesHeldFor("card-0001"));
        }

        // One a day over a 45-day window, both ends included.
        assertThat(mostHeld).isEqualTo(46);
    }

    private Optional<RuleHit> charge(String merchant, long cents, Instant at) {
        return rule.evaluate(TestTransactions.charge()
                .merchant(merchant).category(Category.SUBSCRIPTIONS).cents(cents).at(at).build());
    }
}
