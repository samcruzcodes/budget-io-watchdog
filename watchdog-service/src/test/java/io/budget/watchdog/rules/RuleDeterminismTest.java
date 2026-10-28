package io.budget.watchdog.rules;

import static io.budget.watchdog.rules.TestTransactions.START;
import static io.budget.watchdog.rules.TestTransactions.charge;
import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The six real rules give the same hits for the same transactions, however the cards are interleaved. That's what
 * makes a simulator run reproducible and the rules a fixed baseline for the Phase 6 comparison.
 */
class RuleDeterminismTest {

    @Test
    void theSameTransactionsGiveTheSameHits() {
        List<Transaction> year = aYearOnThreeCards();

        List<Hit> first = hits(year);
        List<Hit> second = hits(year);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(Hit::ruleId).contains(
                "duplicate-charge", "trial-conversion", "price-hike", "spending-spike", "card-testing", "new-country");
    }

    @Test
    void mixingTheCardsInADifferentOrderGivesTheSameHits() {
        List<Transaction> cardByCard = aYearOnThreeCards();
        List<Transaction> allCardsByTime = new ArrayList<>(cardByCard);
        allCardsByTime.sort(Comparator.comparing(Transaction::occurredAt)); // stable, so each card keeps its order

        // Like three consumer threads: the cards interleave differently, but each card's own order is the same.
        assertThat(hits(allCardsByTime)).containsExactlyInAnyOrderElementsOf(hits(cardByCard));
    }

    private record Hit(UUID transactionId, String ruleId) {}

    /** Runs the transactions through a fresh set of the six rules. */
    private static List<Hit> hits(List<Transaction> transactions) {
        RuleEngine engine = new RuleEngine(StartingRules.fresh());
        List<Hit> hits = new ArrayList<>();
        for (Transaction tx : transactions) {
            for (RuleHit hit : engine.evaluate(tx)) {
                hits.add(new Hit(tx.transactionId(), hit.ruleId()));
            }
        }
        return hits;
    }

    /**
     * A year of everyday spending on three cards from a fixed seed, with each kind of issue planted, listed card by
     * card and in time order within each card.
     */
    private static List<Transaction> aYearOnThreeCards() {
        SplittableRandom random = new SplittableRandom(42);
        Instant firstMidnight = START.truncatedTo(ChronoUnit.DAYS);
        List<Transaction> all = new ArrayList<>();
        for (String card : List.of("card-0001", "card-0002", "card-0003")) {
            List<Transaction> year = new ArrayList<>();
            for (int day = 0; day < 365; day++) {
                Instant midnight = firstMidnight.plus(Duration.ofDays(day));
                for (int i = random.nextInt(1, 4); i > 0; i--) {
                    Category category = random.nextBoolean() ? Category.DINING : Category.SHOPPING;
                    year.add(tx(card, "Shop " + random.nextInt(6), category, random.nextLong(500, 6_000), "US",
                            midnight.plusSeconds(random.nextInt(8 * 3_600, 22 * 3_600))));
                }
                Instant early = midnight.plus(Duration.ofHours(5));
                if (day % 30 == 0) { // goes up after day 200
                    year.add(tx(card, "Netflix", Category.SUBSCRIPTIONS, day < 200 ? 1_549 : 1_799, "US", early));
                }
                if (day % 37 == 5) { // a duplicate 40 seconds later
                    year.add(tx(card, "Whole Foods Market", Category.GROCERIES, 8_742, "US", early));
                    year.add(tx(card, "Whole Foods Market", Category.GROCERIES, 8_742, "US", early.plusSeconds(40)));
                }
                if (day % 53 == 20) { // card testing
                    for (int probe = 0; probe < 3; probe++) {
                        Instant at = early.plus(Duration.ofMinutes(5L * probe));
                        year.add(tx(card, "Probe " + probe, Category.SHOPPING, 100, "US", at));
                    }
                    Instant cashOut = early.plus(Duration.ofMinutes(20));
                    year.add(tx(card, "ElectroMart Online", Category.SHOPPING, 25_000, "US", cashOut));
                }
                if (day % 41 == 30) { // a binge
                    for (int purchase = 0; purchase < 4; purchase++) {
                        Instant at = early.plus(Duration.ofMinutes(10L * purchase));
                        year.add(tx(card, "Restaurant " + purchase, Category.DINING, 9_000, "US", at));
                    }
                }
                if (day == 60 || day == 72) { // a free trial, then the paid plan
                    year.add(tx(card, "Peacock", Category.SUBSCRIPTIONS, day == 60 ? 0 : 1_099, "US", early));
                }
                if (day == 150) {
                    year.add(tx(card, "Luxury Goods Outlet", Category.SHOPPING, 81_240, "FR", early));
                }
            }
            year.sort(Comparator.comparing(Transaction::occurredAt));
            all.addAll(year);
        }
        return all;
    }

    private static Transaction tx(String card, String merchant, Category category, long cents, String country,
            Instant at) {
        return charge().card(card).merchant(merchant).category(category).cents(cents).country(country).at(at).build();
    }
}
