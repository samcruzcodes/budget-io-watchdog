package io.budget.watchdog.simulator;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.EdgeCase;
import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the simulated world: same seed gives the same data, every transaction is a valid message, scenarios happen
 * at their configured rates, and each scenario has the shape the detection rules will look for.
 */
class SimulationTest {

    static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final int WARM_UP_DAYS = 30;
    private static final int USERS = 300;
    private static final int YEAR_IN_DAYS = 365;
    private static final double PER_USER_DAY = 0.004;
    private static final SimulatorConfig.Rates TEST_RATES = new SimulatorConfig.Rates(
            0.003,         // duplicate charge, per card purchase
            0.5,           // trial conversion, share of trials
            0.05,          // price hike, per renewal
            PER_USER_DAY,  // spending spike
            PER_USER_DAY,  // card testing
            PER_USER_DAY,  // new country
            0.02,          // double coffee, per morning coffee
            PER_USER_DAY,  // App Store purchases
            PER_USER_DAY,  // big purchase
            0.0,           // trips: off, so nobody skips home spending while abroad
            PER_USER_DAY,  // toll road trip
            PER_USER_DAY); // foreign online order
    private static final Set<Category> CARD_PURCHASES =
            EnumSet.of(Category.GROCERIES, Category.DINING, Category.TRANSPORTATION, Category.SHOPPING);

    /** 300 users for a year, simulated once and shared by the tests that only read it. */
    private static List<PlannedTransaction> year;

    @BeforeAll
    static void simulateAYear() {
        year = simulate(config(42, USERS, TEST_RATES), YEAR_IN_DAYS);
    }

    @Test
    void sameSeedGivesIdenticalTransactionsLabelsAndIds() {
        assertThat(simulate(config(7, 20, TEST_RATES), 90)).isEqualTo(simulate(config(7, 20, TEST_RATES), 90));
    }

    @Test
    void differentSeedGivesDifferentTransactions() {
        assertThat(simulate(config(7, 20, TEST_RATES), 30)).isNotEqualTo(simulate(config(8, 20, TEST_RATES), 30));
    }

    @Test
    void addingUsersDoesntChangeExistingUsers() {
        List<PlannedTransaction> fiveUsers = simulate(config(7, 5, TEST_RATES), 90);
        Set<String> firstFive = fiveUsers.stream().map(PlannedTransaction::userId).collect(toSet());

        List<PlannedTransaction> tenUsers = simulate(config(7, 10, TEST_RATES), 90);

        assertThat(tenUsers.stream().filter(tx -> firstFive.contains(tx.userId())).toList()).isEqualTo(fiveUsers);
    }

    @Test
    void everyTransactionBecomesAValidMessageWithExactlyOneLabel() {
        Instant now = Instant.parse("2026-10-27T12:00:00Z");
        for (PlannedTransaction planned : year) {
            Transaction transaction = planned.toTransaction(now); // the constructors enforce the contract
            GroundTruthLabel label = planned.toGroundTruth();
            assertThat(label.transactionId()).isEqualTo(transaction.transactionId());
            assertThat(label.persona()).isEqualTo(planned.persona().name());
        }
        assertThat(year).extracting(PlannedTransaction::transactionId).doesNotHaveDuplicates();
    }

    @Test
    void transactionsFallInsideTheDayTheyArePlannedForInTimeOrder() {
        Simulation simulation = Simulation.create(config(7, 20, TEST_RATES));
        for (int i = 0; i < 120; i++) {
            LocalDate day = START.plusDays(i);
            List<PlannedTransaction> planned = simulation.planDay(day);
            assertThat(planned).allSatisfy(tx -> assertThat(LocalDate.ofInstant(tx.occurredAt(), ZoneOffset.UTC)).isEqualTo(day));
            assertThat(planned).isSortedAccordingTo(Comparator.comparing(PlannedTransaction::occurredAt));
        }
    }

    @Test
    void nothingIsInjectedDuringTheWarmUp() {
        assertThat(year).filteredOn(tx -> !afterWarmUp(tx))
                .isNotEmpty()
                .allSatisfy(tx -> assertThat(tx.label()).isEqualTo(Label.NORMAL));
    }

    // --- Rates -------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("perUserDayScenarios")
    void perUserDayScenariosMatchTheirConfiguredRate(String scenario, Predicate<Label> kind) {
        double expected = PER_USER_DAY * USERS * (YEAR_IN_DAYS - WARM_UP_DAYS);

        assertThat((double) scenarios(year, kind).size()).isCloseTo(expected, withinPercentage(15));
    }

    static Stream<Arguments> perUserDayScenarios() {
        return Stream.of(
                arguments("spending spike", issue(IssueType.SPENDING_SPIKE)),
                arguments("card testing", issue(IssueType.CARD_TESTING)),
                arguments("new country", issue(IssueType.NEW_COUNTRY)),
                arguments("App Store purchases", edgeCase(EdgeCase.APP_STORE_PURCHASES)),
                arguments("big purchase", edgeCase(EdgeCase.LEGIT_BIG_PURCHASE)),
                arguments("toll road trip", edgeCase(EdgeCase.TOLL_ROAD_TRIP)),
                arguments("foreign online order", edgeCase(EdgeCase.FOREIGN_ONLINE_ORDER)));
    }

    @Test
    void duplicatesMatchTheirRatePerCardPurchase() {
        Set<UUID> duplicateScenarios = scenarios(year, issue(IssueType.DUPLICATE_CHARGE)).stream()
                .map(pair -> pair.getFirst().label().scenarioId())
                .collect(toSet());
        // Purchases that were eligible: card purchases after the warm-up not already in another scenario.
        long purchasesChecked = year.stream()
                .filter(SimulationTest::afterWarmUp)
                .filter(tx -> CARD_PURCHASES.contains(tx.category()))
                .filter(tx -> tx.label().equals(Label.NORMAL) || tx.label().equals(Label.partOf(tx.label().scenarioId()))
                        && duplicateScenarios.contains(tx.label().scenarioId()))
                .count();

        assertThat((double) duplicateScenarios.size()).isCloseTo(0.003 * purchasesChecked, withinPercentage(15));
    }

    @Test
    void priceHikesMatchTheirRatePerRenewal() {
        long renewals = year.stream()
                .filter(SimulationTest::afterWarmUp)
                .filter(tx -> tx.category() == Category.SUBSCRIPTIONS)
                .filter(tx -> tx.label().equals(Label.NORMAL) || tx.label().issueType() == IssueType.PRICE_HIKE)
                .count();
        long hikes = year.stream().filter(tx -> tx.label().issueType() == IssueType.PRICE_HIKE).count();

        assertThat((double) hikes).isCloseTo(0.05 * renewals, withinPercentage(15));
    }

    @Test
    void aboutHalfOfFreeTrialsConvert() {
        long forgotten = year.stream()
                .filter(tx -> tx.category() == Category.SUBSCRIPTIONS)
                .filter(tx -> tx.label().equals(Label.partOf(tx.label().scenarioId())) && tx.label().scenarioId() != null)
                .count();
        long cancelled = year.stream().filter(tx -> tx.label().edgeCase() == EdgeCase.CANCELLED_TRIAL).count();

        assertThat((double) forgotten / (forgotten + cancelled)).isCloseTo(0.5, within(0.06));
    }

    // --- Shapes: what the Phase 2 rules will look for ---------------------------------------------------------------

    @Test
    void duplicateIsTheSameChargePostedAgain5To90SecondsLater() {
        assertThat(scenarios(year, issue(IssueType.DUPLICATE_CHARGE))).isNotEmpty().allSatisfy(pair -> {
            assertThat(pair).hasSize(2);
            PlannedTransaction original = pair.get(0);
            PlannedTransaction copy = pair.get(1);
            assertThat(original.label().issueType()).isEqualTo(IssueType.NONE);
            assertThat(copy.label().issueType()).isEqualTo(IssueType.DUPLICATE_CHARGE);
            assertThat(copy.merchant()).isEqualTo(original.merchant());
            assertThat(copy.amountCents()).isEqualTo(original.amountCents());
            assertThat(Duration.between(original.occurredAt(), copy.occurredAt()))
                    .isBetween(Duration.ofSeconds(5), Duration.ofSeconds(90));
        });
    }

    @Test
    void doubleCoffeeIsTheSameOrderMinutesApartAndLegitimate() {
        assertThat(scenarios(year, edgeCase(EdgeCase.DOUBLE_COFFEE))).isNotEmpty().allSatisfy(pair -> {
            assertThat(pair).hasSize(2).allSatisfy(tx -> assertThat(tx.label().issueType()).isEqualTo(IssueType.NONE));
            assertThat(pair.get(1).merchant()).isEqualTo(pair.get(0).merchant());
            assertThat(pair.get(1).amountCents()).isEqualTo(pair.get(0).amountCents());
            assertThat(Duration.between(pair.get(0).occurredAt(), pair.get(1).occurredAt()))
                    .isBetween(Duration.ofMinutes(1), Duration.ofMinutes(5));
        });
    }

    @Test
    void convertedTrialChargesFullPrice7To30DaysAfterTheSignup() {
        assertThat(scenarios(year, issue(IssueType.TRIAL_CONVERSION))).isNotEmpty().allSatisfy(trial -> {
            assertThat(trial).hasSize(2);
            PlannedTransaction signup = trial.get(0);
            PlannedTransaction conversion = trial.get(1);
            assertThat(signup.amountCents()).isLessThanOrEqualTo(100);
            assertThat(conversion.merchant()).isEqualTo(signup.merchant());
            assertThat(conversion.amountCents()).isGreaterThanOrEqualTo(5 * signup.amountCents()).isGreaterThan(100);
            assertThat(DAYS.between(date(signup), date(conversion))).isBetween(7L, 30L);
        });
    }

    @Test
    void priceHikeIsHigherThanTheSameSubscriptionsPreviousCharge() {
        Map<String, PlannedTransaction> previousCharge = new LinkedHashMap<>();
        int hikes = 0;
        for (PlannedTransaction tx : year) {
            if (tx.category() != Category.SUBSCRIPTIONS) {
                continue;
            }
            String subscription = tx.cardId() + "/" + tx.merchant();
            if (tx.label().issueType() == IssueType.PRICE_HIKE) {
                assertThat(tx.amountCents()).isGreaterThan(previousCharge.get(subscription).amountCents());
                hikes++;
            }
            previousCharge.put(subscription, tx);
        }
        assertThat(hikes).isPositive();
    }

    @Test
    void cardTestingIsTinyChargesThenOneBigChargeWithinAnHour() {
        assertThat(scenarios(year, issue(IssueType.CARD_TESTING))).isNotEmpty().allSatisfy(attack -> {
            assertThat(attack).allSatisfy(tx -> assertThat(tx.label().issueType()).isEqualTo(IssueType.CARD_TESTING));
            assertTinyChargesThenABigOneWithinAnHour(attack);
        });
    }

    @Test
    void tollRoadTripHasTheShapeOfCardTestingButIsLegitimate() {
        assertThat(scenarios(year, edgeCase(EdgeCase.TOLL_ROAD_TRIP))).isNotEmpty().allSatisfy(trip -> {
            assertThat(trip).allSatisfy(tx -> assertThat(tx.label().issueType()).isEqualTo(IssueType.NONE));
            assertTinyChargesThenABigOneWithinAnHour(trip);
        });
    }

    @Test
    void newCountryChargeComesFromACountryTheCardHasNeverUsed() {
        Map<String, Set<String>> countriesSeen = new LinkedHashMap<>();
        int charges = 0;
        for (PlannedTransaction tx : year) {
            Set<String> seen = countriesSeen.computeIfAbsent(tx.cardId(), card -> new java.util.HashSet<>());
            if (tx.label().issueType() == IssueType.NEW_COUNTRY) {
                assertThat(seen).doesNotContain(tx.country());
                charges++;
            }
            seen.add(tx.country());
        }
        assertThat(charges).isPositive();
    }

    @Test
    void tripIsBookedAtHomeBeforeAnySpendingAbroad() {
        SimulatorConfig.Rates tripsOnly = new SimulatorConfig.Rates(0, 0, 0, 0, 0, 0, 0, 0, 0, 0.05, 0, 0);
        List<PlannedTransaction> transactions = simulate(config(7, 20, tripsOnly), 180);

        assertThat(scenarios(transactions, edgeCase(EdgeCase.LEGIT_TRAVEL))).isNotEmpty().allSatisfy(trip -> {
            PlannedTransaction booking = trip.getFirst();
            assertThat(booking.category()).isEqualTo(Category.TRAVEL);
            assertThat(booking.country()).isEqualTo("US");
            List<PlannedTransaction> abroad = trip.subList(1, trip.size());
            if (!abroad.isEmpty()) {
                assertThat(abroad).extracting(PlannedTransaction::country).containsOnly(abroad.getFirst().country());
                assertThat(abroad.getFirst().country()).isNotEqualTo("US");
                assertThat(DAYS.between(date(booking), date(abroad.getFirst()))).isBetween(5L, 25L);
            }
        });
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    static SimulatorConfig config(long seed, int users, SimulatorConfig.Rates rates) {
        Map<Persona, Integer> everyPersona = new EnumMap<>(Persona.class);
        for (Persona persona : Persona.values()) {
            everyPersona.put(persona, 1);
        }
        return new SimulatorConfig(seed, users, everyPersona, START, Double.POSITIVE_INFINITY, 0, WARM_UP_DAYS, rates,
                "unused:9092");
    }

    static List<PlannedTransaction> simulate(SimulatorConfig config, int days) {
        Simulation simulation = Simulation.create(config);
        List<PlannedTransaction> all = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            all.addAll(simulation.planDay(START.plusDays(day)));
        }
        return all;
    }

    /** The transactions of every scenario that contains a transaction matching {@code kind}, each in time order. */
    private static List<List<PlannedTransaction>> scenarios(List<PlannedTransaction> transactions, Predicate<Label> kind) {
        Map<UUID, List<PlannedTransaction>> byScenario = transactions.stream()
                .filter(tx -> tx.label().scenarioId() != null)
                .collect(groupingBy(tx -> tx.label().scenarioId(), LinkedHashMap::new, toList()));
        return byScenario.values().stream()
                .filter(group -> group.stream().anyMatch(tx -> kind.test(tx.label())))
                .toList();
    }

    private static Predicate<Label> issue(IssueType type) {
        return label -> label.issueType() == type;
    }

    private static Predicate<Label> edgeCase(EdgeCase edgeCase) {
        return label -> label.edgeCase() == edgeCase;
    }

    private static boolean afterWarmUp(PlannedTransaction tx) {
        return !date(tx).isBefore(START.plusDays(WARM_UP_DAYS));
    }

    private static LocalDate date(PlannedTransaction tx) {
        return LocalDate.ofInstant(tx.occurredAt(), ZoneOffset.UTC);
    }

    private static void assertTinyChargesThenABigOneWithinAnHour(List<PlannedTransaction> sequence) {
        List<PlannedTransaction> tiny = sequence.subList(0, sequence.size() - 1);
        PlannedTransaction big = sequence.getLast();
        assertThat(tiny).hasSizeBetween(3, 5).allSatisfy(tx -> assertThat(tx.amountCents()).isLessThanOrEqualTo(200));
        assertThat(big.amountCents()).isGreaterThanOrEqualTo(10_000);
        assertThat(Duration.between(sequence.getFirst().occurredAt(), big.occurredAt())).isLessThanOrEqualTo(Duration.ofHours(1));
    }
}
