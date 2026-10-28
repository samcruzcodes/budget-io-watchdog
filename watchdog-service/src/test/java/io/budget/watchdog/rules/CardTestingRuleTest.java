package io.budget.watchdog.rules;

import static io.budget.watchdog.rules.TestTransactions.START;
import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CardTestingRuleTest {

    private static final Duration WINDOW = Duration.ofMinutes(60);

    private final CardTestingRule rule = new CardTestingRule(new CardTestingRule.Settings(WINDOW, 200, 3, 10_000));

    @Test
    void threeTinyChargesThenABigOneFires() {
        assertThat(charge("Bright Futures Charity", 50, minutes(0))).isEmpty();
        assertThat(charge("PixelGames Online", 120, minutes(6))).isEmpty();
        assertThat(charge("SnackHub Vending", 200, minutes(14))).isEmpty();

        Optional<RuleHit> hit = charge("ElectroMart Online", 41_250, minutes(25));

        assertThat(hit).hasValueSatisfying(attack -> {
            assertThat(attack.ruleId()).isEqualTo("card-testing");
            assertThat(attack.issueType()).isEqualTo(IssueType.CARD_TESTING);
            assertThat(attack.explanation()).isEqualTo("3 charges of $2.00 or less in the 25 minutes before a $412.50 "
                    + "charge at ElectroMart Online. Someone may be testing your card number.");
        });
    }

    @Test
    void twoTinyChargesArentEnough() {
        probes(minutes(0), minutes(5));

        assertThat(charge("ElectroMart Online", 41_250, minutes(10))).isEmpty();
    }

    @Test
    void aChargeOverTwoDollarsIsntAProbe() {
        charge("Daily Deals Shop", 200, minutes(0));
        charge("Daily Deals Shop", 200, minutes(1));
        charge("Daily Deals Shop", 201, minutes(2));

        assertThat(charge("ElectroMart Online", 41_250, minutes(3))).isEmpty();
    }

    @Test
    void justUnderOneHundredDollarsDoesntFireAndOneHundredDoes() {
        probes(minutes(0), minutes(1), minutes(2));

        assertThat(charge("GiftCardZone", 9_999, minutes(3))).isEmpty();
        assertThat(charge("GiftCardZone", 10_000, minutes(4))).isPresent();
    }

    @Test
    void aProbeExactlyOneWindowEarlierStillCounts() {
        probes(minutes(0), minutes(20), minutes(40));

        assertThat(charge("ElectroMart Online", 41_250, START.plus(WINDOW))).map(RuleHit::explanation)
                .hasValueSatisfying(text -> assertThat(text)
                        .startsWith("3 charges of $2.00 or less in the 60 minutes"));
    }

    @Test
    void aProbeOneSecondOutsideTheWindowDoesntCount() {
        probes(minutes(0), minutes(20), minutes(40));

        assertThat(charge("ElectroMart Online", 41_250, START.plus(WINDOW).plusSeconds(1))).isEmpty();
    }

    @Test
    void firesOncePerBurst() {
        probes(minutes(0), minutes(1), minutes(2));
        charge("ElectroMart Online", 41_250, minutes(3));

        assertThat(charge("Gadget Outlet Direct", 65_000, minutes(4))).isEmpty();
    }

    @Test
    void aProbeAfterTheBigChargeDoesntCountForItButDoesForTheNextBigCharge() {
        assertThat(charge("PixelGames Online", 150, minutes(0))).isEmpty();
        assertThat(charge("QuickTune Downloads", 120, minutes(5))).isEmpty();
        assertThat(charge("ElectroMart Online", 15_000, minutes(12))).isEmpty(); // only 2 probes before it
        assertThat(charge("SnackHub Vending", 100, minutes(20))).isEmpty();

        // Nothing fired, so nothing was cleared: all three probes are still inside the hour.
        assertThat(charge("GiftCardZone", 20_000, minutes(28))).map(RuleHit::explanation)
                .hasValueSatisfying(text -> assertThat(text)
                        .startsWith("3 charges of $2.00 or less in the 28 minutes before a $200.00 charge"));
    }

    @Test
    void aBigChargeWithNoProbesDoesntFire() {
        assertThat(charge("Best Buy", 89_900, minutes(0))).isEmpty();
    }

    @Test
    void probesOnAnotherCardDontCount() {
        probes(minutes(0), minutes(1), minutes(2));

        assertThat(rule.evaluate(TestTransactions.charge().card("card-0002").merchant("ElectroMart Online")
                .cents(41_250).at(minutes(3)).build())).isEmpty();
    }

    // Look-alike from the simulator

    @Test
    void aTollRoadTripFiresAsAnExpectedFalsePositive() {
        toll(150, minutes(0));
        toll(125, minutes(8));
        toll(200, minutes(19));

        Optional<RuleHit> hotel = rule.evaluate(TestTransactions.charge()
                .merchant("Holiday Inn Express").category(Category.TRAVEL).cents(16_400).at(minutes(30)).build());

        assertThat(hotel).isPresent();
    }

    // Memory

    @Test
    void holdsOnlyTheLastWindowOfSmallCharges() {
        int mostHeld = 0;
        for (Instant at = START; at.isBefore(START.plus(Duration.ofDays(365))); at = at.plus(Duration.ofMinutes(5))) {
            charge("SnackHub Vending", 100, at);
            mostHeld = Math.max(mostHeld, rule.probesHeldFor("card-0001"));
        }

        // Every 5 minutes over a 60-minute window, both ends included.
        assertThat(mostHeld).isEqualTo(13);
    }

    private Optional<RuleHit> charge(String merchant, long cents, Instant at) {
        return rule.evaluate(TestTransactions.charge().merchant(merchant).cents(cents).at(at).build());
    }

    private void probes(Instant... times) {
        for (Instant at : times) {
            assertThat(charge("PixelGames Online", 99, at)).isEmpty();
        }
    }

    private void toll(long cents, Instant at) {
        rule.evaluate(TestTransactions.charge()
                .merchant("E-ZPass").category(Category.TRANSPORTATION).cents(cents).at(at).build());
    }

    private static Instant minutes(long minutes) {
        return START.plus(Duration.ofMinutes(minutes));
    }
}
