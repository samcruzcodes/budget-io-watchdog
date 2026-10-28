package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.EdgeCase;
import io.budget.watchdog.schema.IssueType;
import java.time.Instant;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Groceries, dining, coffee, transportation, and shopping, plus the scenarios hidden among them: binge days (an
 * injected issue) and double coffees, big purchases, and toll road trips (legitimate look-alikes).
 */
final class EverydaySpending implements SpendingBehavior {

    private final SimulatorConfig config;

    EverydaySpending(SimulatorConfig config) {
        this.config = config;
    }

    @Override
    public void planDay(DayPlan plan) {
        SimulatedUser user = plan.user();
        if (user.isAbroad(plan.day())) {
            return; // away on a trip: Travel planned today's spending
        }
        RandomGenerator random = user.random;
        Persona persona = user.persona;
        boolean scenariosActive = config.scenariosActiveOn(plan.day());

        for (int i = Draw.countToday(random, persona.groceries.perWeek()); i > 0; i--) {
            plan.add(plan.at(Draw.timeBetween(random, 9, 20)), user.groceryStore, Category.GROCERIES,
                    Draw.amountNear(random, persona.groceries.typicalCents(), 0.35));
        }
        for (int i = Draw.countToday(random, persona.dining.perWeek()); i > 0; i--) {
            boolean lunch = random.nextBoolean();
            Instant time = plan.at(lunch ? Draw.timeBetween(random, 11, 14) : Draw.timeBetween(random, 18, 22));
            plan.add(time, Draw.pick(random, user.restaurants), Category.DINING,
                    Draw.amountNear(random, persona.dining.typicalCents(), 0.4));
        }
        if (Draw.chance(random, persona.coffeeChance)) {
            buyCoffee(plan, scenariosActive);
        }
        List<String> transportation = persona.ownsCar ? Merchants.GAS_STATIONS : Merchants.RIDESHARE;
        for (int i = Draw.countToday(random, persona.transportation.perWeek()); i > 0; i--) {
            plan.add(plan.at(Draw.timeBetween(random, 7, 22)), Draw.pick(random, transportation), Category.TRANSPORTATION,
                    Draw.amountNear(random, persona.transportation.typicalCents(), 0.3));
        }
        for (int i = Draw.countToday(random, persona.shopping.perWeek()); i > 0; i--) {
            plan.add(plan.at(Draw.timeBetween(random, 9, 22)), Draw.pick(random, Merchants.SHOPS), Category.SHOPPING,
                    Draw.amountNear(random, persona.shopping.typicalCents(), 0.6));
        }

        if (scenariosActive) {
            SimulatorConfig.Rates rates = config.rates();
            if (Draw.chance(random, rates.spendingSpike())) {
                bingeDay(plan);
            }
            if (Draw.chance(random, rates.bigPurchase())) {
                bigPurchase(plan);
            }
            if (Draw.chance(random, rates.tollRoadTrip())) {
                tollRoadTrip(plan);
            }
        }
    }

    private void buyCoffee(DayPlan plan, boolean scenariosActive) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        Instant morning = plan.at(Draw.timeBetween(random, 7, 10));
        PlannedTransaction coffee = plan.add(morning, user.coffeeShop, Category.DINING, user.coffeeCents);

        if (scenariosActive && Draw.chance(random, config.rates().doubleCoffee())) {
            // Look-alike: a second coffee for a friend a few minutes later. Same shop, same price, but legitimate.
            Label label = Label.edgeCase(EdgeCase.DOUBLE_COFFEE, user.newScenarioId());
            plan.relabel(coffee, label);
            plan.add(morning.plusSeconds(Draw.between(random, 60, 300)), user.coffeeShop, Category.DINING,
                    user.coffeeCents, SimulatedUser.HOME_COUNTRY, label);
        } else if (Draw.chance(random, 0.15)) {
            // An afternoon coffee: the same order hours later. Normal, and a duplicate-charge rule must not flag it.
            plan.add(plan.at(Draw.timeBetween(random, 14, 17)), user.coffeeShop, Category.DINING, user.coffeeCents);
        }
    }

    /** Injected issue: a binge day, several extra purchases in one category. */
    private void bingeDay(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        Category category = random.nextBoolean() ? Category.DINING : Category.SHOPPING;
        if (plan.hasScenarioIn(category)) {
            return;
        }
        boolean dining = category == Category.DINING;
        Persona.Habit habit = dining ? user.persona.dining : user.persona.shopping;
        for (int i = Draw.between(random, 3, 5); i > 0; i--) {
            String merchant = Draw.pick(random, dining ? Merchants.RESTAURANTS : Merchants.SHOPS);
            plan.add(plan.at(Draw.timeBetween(random, 10, 23)), merchant, category,
                    Draw.amountNear(random, habit.typicalCents() * 2, 0.4));
        }
        // The spike is a property of the day's total, so every purchase in the category today belongs to it.
        plan.relabelCategory(category, Label.issue(IssueType.SPENDING_SPIKE, user.newScenarioId()));
    }

    /** Look-alike: a planned large purchase, such as a laptop. */
    private void bigPurchase(DayPlan plan) {
        if (plan.hasScenarioIn(Category.SHOPPING)) {
            return;
        }
        SimulatedUser user = plan.user();
        Merchants.Offer item = Draw.pick(user.random, Merchants.BIG_TICKET_ITEMS);
        plan.add(plan.at(Draw.timeBetween(user.random, 11, 19)), item.merchant(), Category.SHOPPING, item.priceCents(),
                SimulatedUser.HOME_COUNTRY, Label.edgeCase(EdgeCase.LEGIT_BIG_PURCHASE, user.newScenarioId()));
    }

    /** Look-alike: several small tolls, then a hotel, all within an hour. Shaped like card testing, but legitimate. */
    private void tollRoadTrip(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        Label label = Label.edgeCase(EdgeCase.TOLL_ROAD_TRIP, user.newScenarioId());
        Instant time = plan.at(Draw.timeBetween(random, 9, 18));
        for (int i = Draw.between(random, 3, 5); i > 0; i--) {
            plan.add(time, Merchants.TOLL_ROAD, Category.TRANSPORTATION, Draw.centsBetween(random, 125, 200),
                    SimulatedUser.HOME_COUNTRY, label);
            time = time.plusSeconds(Draw.between(random, 5 * 60, 12 * 60));
        }
        plan.add(time, Merchants.ROADSIDE_HOTEL, Category.TRAVEL, Draw.centsBetween(random, 12_000, 22_000),
                SimulatedUser.HOME_COUNTRY, label);
    }
}
