package io.budget.watchdog.rules;

import static io.budget.watchdog.rules.TestTransactions.START;
import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DuplicateChargeRuleTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final DuplicateChargeRule rule = new DuplicateChargeRule(new DuplicateChargeRule.Settings(WINDOW));

    @Test
    void sameMerchantAndAmountSecondsLaterFires() {
        charge("Whole Foods Market", 8_742, START);

        Optional<RuleHit> hit = charge("Whole Foods Market", 8_742, START.plusSeconds(40));

        assertThat(hit).hasValueSatisfying(duplicate -> {
            assertThat(duplicate.ruleId()).isEqualTo("duplicate-charge");
            assertThat(duplicate.issueType()).isEqualTo(IssueType.DUPLICATE_CHARGE);
            assertThat(duplicate.explanation()).isEqualTo("Whole Foods Market charged $87.42 twice, 40 seconds apart.");
        });
    }

    @Test
    void theFirstChargeDoesntFire() {
        assertThat(charge("Whole Foods Market", 8_742, START)).isEmpty();
    }

    @Test
    void aDifferentAmountDoesntFire() {
        charge("Whole Foods Market", 8_742, START);

        assertThat(charge("Whole Foods Market", 8_743, START.plusSeconds(40))).isEmpty();
    }

    @Test
    void aDifferentMerchantDoesntFire() {
        charge("Whole Foods Market", 8_742, START);

        assertThat(charge("Trader Joe's", 8_742, START.plusSeconds(40))).isEmpty();
    }

    @Test
    void exactlyOneWindowApartStillFires() {
        charge("Shell", 5_210, START);

        assertThat(charge("Shell", 5_210, START.plus(WINDOW))).map(RuleHit::explanation)
                .hasValue("Shell charged $52.10 twice, 10 minutes apart.");
    }

    @Test
    void oneSecondMoreThanTheWindowDoesntFire() {
        charge("Shell", 5_210, START);

        assertThat(charge("Shell", 5_210, START.plus(WINDOW).plusSeconds(1))).isEmpty();
    }

    @Test
    void aThirdIdenticalChargeFiresAgainMeasuredFromTheLatestOne() {
        charge("Chipotle", 1_385, START);
        charge("Chipotle", 1_385, START.plusSeconds(30));

        assertThat(charge("Chipotle", 1_385, START.plusSeconds(90))).map(RuleHit::explanation)
                .hasValue("Chipotle charged $13.85 again, 60 seconds after an identical charge.");
    }

    @Test
    void anotherCardsIdenticalChargeDoesntCount() {
        charge("card-0001", "Target", 4_999, START);

        assertThat(charge("card-0002", "Target", 4_999, START.plusSeconds(40))).isEmpty();
        assertThat(charge("card-0002", "Target", 4_999, START.plusSeconds(80))).isPresent();
    }

    // Look-alikes from the simulator

    @Test
    void aDoubleCoffeeForAFriendFiresAsAnExpectedFalsePositive() {
        coffee(START);

        assertThat(coffee(START.plusSeconds(180))).map(RuleHit::explanation)
                .hasValue("Blue Bottle Coffee charged $5.45 twice, 3 minutes apart.");
    }

    @Test
    void theSameCoffeeInTheAfternoonDoesntFire() {
        coffee(START);

        assertThat(coffee(START.plus(Duration.ofHours(6)))).isEmpty();
    }

    // Memory

    @Test
    void holdsOnlyTheLastWindowOfChargesForACard() {
        int mostHeld = 0;
        for (Instant at = START; at.isBefore(START.plus(Duration.ofDays(365))); at = at.plus(Duration.ofMinutes(2))) {
            charge("Target", 2_500, at);
            mostHeld = Math.max(mostHeld, rule.chargesHeldFor("card-0001"));
        }

        // Charges 0, 2, 4, 6, 8, and 10 minutes before the latest one: the 10-minute window, ends included.
        assertThat(mostHeld).isEqualTo(6);
    }

    private Optional<RuleHit> charge(String merchant, long cents, Instant at) {
        return charge("card-0001", merchant, cents, at);
    }

    private Optional<RuleHit> charge(String cardId, String merchant, long cents, Instant at) {
        return rule.evaluate(TestTransactions.charge().card(cardId).merchant(merchant).cents(cents).at(at).build());
    }

    private Optional<RuleHit> coffee(Instant at) {
        return rule.evaluate(TestTransactions.charge()
                .merchant("Blue Bottle Coffee").category(Category.DINING).cents(545).at(at).build());
    }
}
