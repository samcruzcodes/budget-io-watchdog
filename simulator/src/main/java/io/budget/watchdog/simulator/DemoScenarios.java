package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The scenarios someone can trigger on purpose from the dashboard: one per detection rule. Each run is a short,
 * fixed script of transactions on a brand-new card, built to meet the rule's real conditions with its default
 * thresholds (watchdog.rules.* in the watchdog's application.yml).
 *
 * <p>Several rules need history before they can judge anything: new-country wants 20 earlier charges, price-hike two
 * steady months. Rules only look at event time ({@code occurredAt}), so a run sends that history too, dated in the
 * past, and then the suspicious part dated "now". Everything is published at once and arrives in order, because one
 * card's messages share a Kafka partition.
 *
 * <p>Nothing here touches the seeded simulation: a run has its own card, its own IDs, and no random numbers, so the
 * same seed still produces the same seeded transactions whether or not anyone runs a scenario.
 */
final class DemoScenarios {

    /** Every demo card belongs to this one user, so demo data is easy to spot and to filter. */
    static final String USER = "demo-user";

    /** The label's persona for demo transactions (the seeded ones carry a real persona's name). */
    static final String PERSONA = "DEMO";

    static final List<IssueType> TYPES = List.of(IssueType.CARD_TESTING, IssueType.DUPLICATE_CHARGE,
            IssueType.NEW_COUNTRY, IssueType.PRICE_HIKE, IssueType.SPENDING_SPIKE, IssueType.TRIAL_CONVERSION);

    /** What a step is for: history the rule needs first, or part of the problem itself. */
    enum Role { SETUP, SUSPICIOUS }

    /** One transaction of a run, with a note for the person watching. */
    record Step(UUID transactionId, Role role, Instant occurredAt, String merchant, Category category,
            long amountCents, String country, String note) {}

    /**
     * A planned run. {@code scenarioId} goes into every step's ground-truth label, which is how the watchdog's API
     * finds the run's transactions and alerts again. {@code expectedDetector} is the rule that should fire.
     */
    record Run(UUID scenarioId, IssueType type, String userId, String cardId, String expectedDetector,
            List<Step> steps) {

        Transaction transaction(Step step, Instant publishedAt) {
            return new Transaction(Transaction.SCHEMA_VERSION, step.transactionId(), Transaction.DEMO_SOURCE, userId,
                    cardId, step.merchant(), step.category(), step.amountCents(), "USD", step.country(),
                    step.occurredAt(), publishedAt);
        }

        /** The answer key: the suspicious steps carry the issue type, the setup steps only the scenario ID. */
        GroundTruthLabel label(Step step) {
            IssueType issue = step.role() == Role.SUSPICIOUS ? type : IssueType.NONE;
            return new GroundTruthLabel(GroundTruthLabel.SCHEMA_VERSION, step.transactionId(), PERSONA, issue,
                    scenarioId, null);
        }
    }

    private DemoScenarios() {
    }

    /**
     * @param scenarioId a new random ID per run: the card and every transaction ID are derived from it, so running
     *                   the same scenario again can never repeat an ID the watchdog has already stored
     * @param now        the simulated moment the suspicious part happens; history is dated back from it
     */
    static Run plan(IssueType type, UUID scenarioId, Instant now) {
        Script script = new Script(scenarioId);
        switch (type) {
            case CARD_TESTING -> cardTesting(script, now);
            case DUPLICATE_CHARGE -> duplicateCharge(script, now);
            case NEW_COUNTRY -> newCountry(script, now);
            case PRICE_HIKE -> priceHike(script, now);
            case SPENDING_SPIKE -> spendingSpike(script, now);
            case TRIAL_CONVERSION -> trialConversion(script, now);
            case NONE -> throw new IllegalArgumentException("NONE isn't a scenario");
        }
        String card = "demo-" + scenarioId.toString().substring(0, 8);
        return new Run(scenarioId, type, USER, card, detectorFor(type), List.copyOf(script.steps));
    }

    /** "CARD_TESTING" -> "card-testing": every rule's ID is its issue type in lower case with dashes. */
    static String detectorFor(IssueType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    // card-testing: at least 3 charges of $2.00 or less in the 60 minutes before a charge of $100 or more.
    private static void cardTesting(Script script, Instant now) {
        script.suspicious(now.minus(minutes(11)), "Bright Futures Charity", Category.SHOPPING, 100, "probe 1: a $1.00 test charge");
        script.suspicious(now.minus(minutes(8)), "PixelGames Online", Category.SHOPPING, 50, "probe 2");
        script.suspicious(now.minus(minutes(5)), "QuickTune Downloads", Category.SHOPPING, 199, "probe 3");
        script.suspicious(now.minus(minutes(2)), "SnackHub Vending", Category.SHOPPING, 75, "probe 4");
        script.suspicious(now, "ElectroMart Online", Category.SHOPPING, 48_900,
                "the real purchase, once the card number is known to work");
    }

    // duplicate-charge: the same merchant charges the same amount on the same card within 10 minutes.
    private static void duplicateCharge(Script script, Instant now) {
        script.setup(now.minusSeconds(24), "Whole Foods Market", Category.GROCERIES, 6_427, "the original charge");
        script.suspicious(now, "Whole Foods Market", Category.GROCERIES, 6_427, "the same charge again, 24 seconds later");
    }

    // new-country: a country the card hasn't used, after at least 20 earlier charges, with no TRAVEL purchase in the
    // last 30 days. (A card's first 20 charges teach the rule where it's normally used.)
    private static void newCountry(Script script, Instant now) {
        String[] merchants = {"Trader Joe's", "Shell", "Safeway", "Uber", "Kroger", "Chevron"};
        for (int day = 22; day >= 1; day--) {
            Category category = day % 2 == 0 ? Category.GROCERIES : Category.TRANSPORTATION;
            script.setup(now.minus(days(day)), merchants[day % merchants.length], category, 2_150 + day * 137L,
                    day == 22 ? "22 days of ordinary charges at home, so the rule knows this card" : "an ordinary charge at home");
        }
        script.steps.add(script.step(Role.SUSPICIOUS, now, "Luxury Goods Outlet", Category.SHOPPING, 81_240, "FR",
                "a charge in France, with no flight or hotel bought first"));
    }

    // price-hike: two charges 26 to 35 days apart at the same price (within 1%), then one more than 1% higher.
    private static void priceHike(Script script, Instant now) {
        script.setup(now.minus(days(60)), "Netflix", Category.SUBSCRIPTIONS, 1_549, "two months ago: $15.49");
        script.setup(now.minus(days(30)), "Netflix", Category.SUBSCRIPTIONS, 1_549, "last month: $15.49 again, a steady price");
        script.suspicious(now, "Netflix", Category.SUBSCRIPTIONS, 1_849, "this month: $18.49, about 19% more");
    }

    // spending-spike: today's dining total is at least $50 and at least 3 standard deviations above this card's usual
    // dining day, after at least 14 earlier days with dining spending.
    private static void spendingSpike(Script script, Instant now) {
        long[] usualDays = {2_800, 3_400, 2_200, 3_900, 3_100, 2_600, 3_600, 2_400, 3_300, 2_900, 3_700, 2_500, 3_200,
                3_000, 2_700, 3_500};
        for (int i = 0; i < usualDays.length; i++) {
            int daysAgo = usualDays.length - i;
            script.setup(now.minus(days(daysAgo)), i % 2 == 0 ? "Chipotle" : "Olive Garden", Category.DINING,
                    usualDays[i], i == 0 ? "16 ordinary days of eating out, about $30 each" : "an ordinary dining day");
        }
        // The rule adds up each UTC day, so today's three purchases must not slip into yesterday when "now" is just
        // after midnight: none is dated before the start of today.
        Instant startOfToday = now.truncatedTo(ChronoUnit.DAYS);
        script.suspicious(notBefore(startOfToday, now.minus(minutes(190))), "Chipotle", Category.DINING, 2_640,
                "the first order today: still a normal day");
        script.suspicious(notBefore(startOfToday, now.minus(minutes(45))), "Sushi Zen", Category.DINING, 18_750,
                "a $187.50 dinner pushes today's total far past the usual");
        script.suspicious(now, "Uber Eats", Category.DINING, 4_215, "and a late delivery order on top");
    }

    // trial-conversion: a charge of $1.00 or less, then the same merchant charges more than $1.00 and at least 5 times
    // as much, 5 to 45 days later.
    private static void trialConversion(Script script, Instant now) {
        script.setup(now.minus(days(14)), "Peacock", Category.SUBSCRIPTIONS, 0, "signing up for a free trial: a $0.00 charge");
        script.suspicious(now, "Peacock", Category.SUBSCRIPTIONS, 1_099, "14 days later the trial becomes a $10.99 plan");
    }

    private static Instant notBefore(Instant earliest, Instant time) {
        return time.isBefore(earliest) ? earliest : time;
    }

    private static Duration days(int days) {
        return Duration.ofDays(days);
    }

    private static Duration minutes(int minutes) {
        return Duration.ofMinutes(minutes);
    }

    /** Collects a run's steps and gives each transaction an ID derived from the scenario ID and its position. */
    private static final class Script {

        private final UUID scenarioId;
        private final List<Step> steps = new ArrayList<>();

        Script(UUID scenarioId) {
            this.scenarioId = scenarioId;
        }

        void setup(Instant occurredAt, String merchant, Category category, long amountCents, String note) {
            steps.add(step(Role.SETUP, occurredAt, merchant, category, amountCents, SimulatedUser.HOME_COUNTRY, note));
        }

        void suspicious(Instant occurredAt, String merchant, Category category, long amountCents, String note) {
            steps.add(step(Role.SUSPICIOUS, occurredAt, merchant, category, amountCents, SimulatedUser.HOME_COUNTRY, note));
        }

        Step step(Role role, Instant occurredAt, String merchant, Category category, long amountCents, String country,
                String note) {
            UUID id = UUID.nameUUIDFromBytes(("demo/" + scenarioId + "/" + steps.size()).getBytes(StandardCharsets.UTF_8));
            return new Step(id, role, occurredAt, merchant, category, amountCents, country, note);
        }
    }
}
