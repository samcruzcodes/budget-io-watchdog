package io.budget.watchdog.simulator;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * One simulated person and their card. Habits are chosen once, from the user's own seeded random generator; the
 * behaviors then read and update the changing state (subscriptions, scheduled charges, trips) day by day.
 */
final class SimulatedUser {

    static final String HOME_COUNTRY = "US";

    final String userId;
    final String cardId;
    final Persona persona;
    final RandomGenerator random;
    private final long seed;
    private long transactionCount;
    private long scenarioCount;

    // Habits, chosen once.
    final List<String> employers;   // one per earner
    final int payCycleOffset;       // which day of the two-week pay cycle is payday
    final String groceryStore;
    final List<String> restaurants;
    final String coffeeShop;
    final long coffeeCents;         // their usual order
    final List<Utility> utilities = new ArrayList<>();

    // State that changes as the days go by.
    final List<Subscription> subscriptions = new ArrayList<>();
    final Set<String> triedTrials = new HashSet<>();
    final Set<String> countriesUsed = new HashSet<>(Set.of(HOME_COUNTRY));
    private final List<ScheduledCharge> scheduled = new ArrayList<>();
    Trip trip; // a booked or current trip, or null

    record Utility(String merchant, long typicalCents, boolean varies, int billingDay) {}

    record Trip(String country, LocalDate departure, LocalDate returnDate) {
        boolean covers(LocalDate day) {
            return !day.isBefore(departure) && !day.isAfter(returnDate);
        }
    }

    /**
     * @param random this user's own generator, split from the run's seeded generator (see {@link Simulation#create})
     */
    SimulatedUser(int number, Persona persona, long seed, RandomGenerator random, LocalDate startDate) {
        this.userId = "user-%04d".formatted(number);
        this.cardId = "card-%04d".formatted(number);
        this.persona = persona;
        this.seed = seed;
        this.random = random;

        this.employers = persona.income.schedule() == Persona.PaySchedule.MONTHLY
                ? List.of(Merchants.RETIREMENT_PAYER)
                : Draw.pickDistinct(random, Merchants.EMPLOYERS, persona.income.earners());
        this.payCycleOffset = random.nextInt(14);
        this.groceryStore = Draw.pick(random, Merchants.GROCERY_STORES);
        this.restaurants = Draw.pickDistinct(random, Merchants.RESTAURANTS, 4);
        this.coffeeShop = Draw.pick(random, Merchants.COFFEE_SHOPS);
        this.coffeeCents = Draw.between(random, 70, 130) * 5L; // $3.50 to $6.50, in 5-cent steps

        for (Merchants.UtilityAccount account : Merchants.UTILITIES) {
            long typical = Math.round(account.typicalCents() * persona.utilitiesFactor);
            utilities.add(new Utility(account.merchant(), typical, account.varies(), Draw.between(random, 1, 28)));
        }
        for (Merchants.Offer offer : Draw.pickDistinct(random, Merchants.SUBSCRIPTIONS, persona.subscriptions)) {
            subscriptions.add(Subscription.existing(offer, Draw.between(random, 1, 28), startDate));
        }
    }

    UUID nextTransactionId() {
        return nameBasedId("transaction/" + transactionCount++);
    }

    UUID newScenarioId() {
        return nameBasedId("scenario/" + scenarioCount++);
    }

    /** Same seed, same user, same count: same ID. That is what makes a re-run produce identical transaction IDs. */
    private UUID nameBasedId(String name) {
        return UUID.nameUUIDFromBytes((seed + "/" + userId + "/" + name).getBytes(StandardCharsets.UTF_8));
    }

    boolean isAbroad(LocalDate day) {
        return trip != null && trip.covers(day);
    }

    void schedule(ScheduledCharge charge) {
        scheduled.add(charge);
    }

    /** Removes and returns the charges scheduled for {@code day}, in the order they were scheduled. */
    List<ScheduledCharge> takeScheduledFor(LocalDate day) {
        List<ScheduledCharge> due = scheduled.stream().filter(charge -> charge.date().equals(day)).toList();
        scheduled.removeIf(charge -> charge.date().equals(day));
        return due;
    }
}
