package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.EdgeCase;
import java.time.LocalDate;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Two look-alikes for the new-country rule: trips abroad (the flight is booked at home first, then the spending
 * abroad happens) and one-off orders from foreign online shops.
 */
final class Travel implements SpendingBehavior {

    private final SimulatorConfig config;

    Travel(SimulatorConfig config) {
        this.config = config;
    }

    @Override
    public void planDay(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        if (user.trip != null && plan.day().isAfter(user.trip.returnDate())) {
            user.trip = null; // home again
        }
        if (!config.scenariosActiveOn(plan.day())) {
            return;
        }
        if (user.trip == null && Draw.chance(random, config.rates().trip() * user.persona.travelFactor)) {
            bookTrip(plan);
        }
        if (Draw.chance(random, config.rates().foreignOnlineOrder())) {
            foreignOnlineOrder(plan);
        }
    }

    /** Books the flight today and schedules the spending abroad for the days of the trip. */
    private void bookTrip(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        Merchants.Destination destination = Draw.pick(random, Merchants.DESTINATIONS);
        String country = destination.country();
        Label label = Label.edgeCase(EdgeCase.LEGIT_TRAVEL, user.newScenarioId());
        LocalDate departure = plan.day().plusDays(Draw.between(random, 5, 25));
        int nights = Draw.between(random, 3, 8);
        LocalDate returnDate = departure.plusDays(nights);
        user.trip = new SimulatedUser.Trip(country, departure, returnDate);
        user.countriesUsed.add(country);

        plan.add(plan.at(Draw.timeBetween(random, 19, 23)), Draw.pick(random, Merchants.AIRLINES), Category.TRAVEL,
                Draw.centsBetween(random, 35_000, 120_000), SimulatedUser.HOME_COUNTRY, label);
        user.schedule(new ScheduledCharge(departure, Draw.timeBetween(random, 15, 20), destination.hotel(), Category.TRAVEL,
                Draw.centsBetween(random, 12_000, 30_000) * nights, country, label));
        for (LocalDate day = departure; !day.isAfter(returnDate); day = day.plusDays(1)) {
            for (int meal = Draw.between(random, 1, 3); meal > 0; meal--) {
                user.schedule(new ScheduledCharge(day, Draw.timeBetween(random, 11, 22), destination.restaurant(),
                        Category.DINING, Draw.amountNear(random, 3_000, 0.4), country, label));
            }
            if (Draw.chance(random, 0.7)) {
                user.schedule(new ScheduledCharge(day, Draw.timeBetween(random, 8, 21), destination.transit(),
                        Category.TRANSPORTATION, Draw.amountNear(random, 800, 0.5), country, label));
            }
            if (Draw.chance(random, 0.3)) {
                user.schedule(new ScheduledCharge(day, Draw.timeBetween(random, 10, 20), destination.shop(),
                        Category.SHOPPING, Draw.amountNear(random, 6_000, 0.6), country, label));
            }
        }
    }

    /** One order from a shop in a country the card hasn't been used in, with no trip. */
    private void foreignOnlineOrder(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        List<Merchants.ForeignShop> newCountries = Merchants.FOREIGN_SHOPS.stream()
                .filter(shop -> !user.countriesUsed.contains(shop.country()))
                .toList();
        if (newCountries.isEmpty()) {
            return;
        }
        Merchants.ForeignShop shop = Draw.pick(random, newCountries);
        user.countriesUsed.add(shop.country());
        plan.add(plan.at(Draw.timeBetween(random, 9, 23)), shop.merchant(), Category.SHOPPING,
                Draw.amountNear(random, 7_500, 0.4), shop.country(),
                Label.edgeCase(EdgeCase.FOREIGN_ONLINE_ORDER, user.newScenarioId()));
    }
}
