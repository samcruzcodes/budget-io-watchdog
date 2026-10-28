package io.budget.watchdog.rules;

import static io.budget.watchdog.rules.TestTransactions.START;
import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NewCountryRuleTest {

    private static final Duration LOOKBACK = Duration.ofDays(30);

    private final NewCountryRule rule = new NewCountryRule(new NewCountryRule.Settings(20, LOOKBACK));

    @Test
    void aChargeFromANewCountryAfterTwentyHomeChargesFires() {
        homeCharges(20);

        Optional<RuleHit> hit = charge("Luxury Goods Outlet", "FR", 81_240, days(40));

        assertThat(hit).hasValueSatisfying(fraud -> {
            assertThat(fraud.ruleId()).isEqualTo("new-country");
            assertThat(fraud.issueType()).isEqualTo(IssueType.NEW_COUNTRY);
            assertThat(fraud.explanation()).isEqualTo("$812.40 at Luxury Goods Outlet (France), a country this card "
                    + "isn't normally used in, with no travel purchases in the last 30 days.");
        });
    }

    @Test
    void aKnownCountryDoesntFire() {
        homeCharges(20);

        assertThat(charge("Target", "US", 4_200, days(40))).isEmpty();
    }

    @Test
    void withOnlyNineteenEarlierChargesTheCardLearnsTheCountryQuietly() {
        homeCharges(19);

        assertThat(charge("Uniqlo Japan", "JP", 6_400, days(40))).isEmpty();
        assertThat(charge("Uniqlo Japan", "JP", 5_100, days(41))).isEmpty();
    }

    @Test
    void travelExactlyThirtyDaysEarlierExplainsTheCountry() {
        homeCharges(20);
        flight(days(30));

        assertThat(charge("Cafe de Flore", "FR", 3_100, days(30).plus(LOOKBACK))).isEmpty();
        assertThat(charge("Galeries Lafayette", "FR", 9_800, days(90))).isEmpty(); // learned during the trip
    }

    @Test
    void travelOneSecondMoreThanThirtyDaysEarlierDoesnt() {
        homeCharges(20);
        flight(days(30));

        assertThat(charge("Cafe de Flore", "FR", 3_100, days(30).plus(LOOKBACK).plusSeconds(1))).isPresent();
    }

    @Test
    void aFlaggedCountryFiresAgainOnItsNextCharge() {
        homeCharges(20);
        charge("Luxury Goods Outlet", "FR", 81_240, days(40));

        assertThat(charge("Digital Gift Cards", "FR", 25_000, days(40).plusSeconds(3_600))).isPresent();
    }

    @Test
    void aHotelAbroadCantCountAsTheTravelThatExcusesItself() {
        homeCharges(20);

        assertThat(rule.evaluate(TestTransactions.charge().merchant("Hotel Le Marais").category(Category.TRAVEL)
                .country("FR").cents(96_000).at(days(40)).build())).isPresent();
    }

    @Test
    void anotherCardsCountriesAndTravelDontCount() {
        homeCharges(20);
        rule.evaluate(TestTransactions.charge().card("card-0002").merchant("Delta Air Lines")
                .category(Category.TRAVEL).cents(64_000).at(days(35)).build());
        rule.evaluate(TestTransactions.charge().card("card-0002").merchant("Cafe de Flore")
                .country("FR").cents(3_100).at(days(36)).build());

        assertThat(charge("Luxury Goods Outlet", "FR", 81_240, days(40))).isPresent();
    }

    // Look-alikes from the simulator

    @Test
    void aTripBookedAtHomeFirstStaysQuiet() {
        homeCharges(20);
        flight(days(30));

        Instant departure = days(42);
        assertThat(rule.evaluate(TestTransactions.charge().merchant("Hotel Le Marais").category(Category.TRAVEL)
                .country("FR").cents(96_000).at(departure.plus(Duration.ofHours(7))).build())).isEmpty();
        assertThat(charge("RATP Paris", "FR", 420, departure.plus(Duration.ofHours(1)))).isEmpty();
        assertThat(charge("Cafe de Flore", "FR", 3_100, departure.plus(Duration.ofDays(2)))).isEmpty();
    }

    @Test
    void aForeignOnlineOrderWithNoTripFiresAsAnExpectedFalsePositive() {
        homeCharges(20);

        assertThat(charge("ASOS", "GB", 7_500, days(40))).map(RuleHit::explanation)
                .hasValueSatisfying(text -> assertThat(text).startsWith("$75.00 at ASOS (United Kingdom)"));
    }

    /** {@code count} charges at home, one a day from {@code START}. */
    private void homeCharges(int count) {
        for (int i = 0; i < count; i++) {
            assertThat(charge("Target", "US", 2_500, days(i))).isEmpty();
        }
    }

    /** A flight booked at home: a TRAVEL charge in the US. */
    private void flight(Instant at) {
        rule.evaluate(TestTransactions.charge()
                .merchant("Delta Air Lines").category(Category.TRAVEL).cents(64_000).at(at).build());
    }

    private Optional<RuleHit> charge(String merchant, String country, long cents, Instant at) {
        return rule.evaluate(TestTransactions.charge().merchant(merchant).country(country).cents(cents).at(at).build());
    }

    private static Instant days(long days) {
        return START.plus(Duration.ofDays(days));
    }
}
