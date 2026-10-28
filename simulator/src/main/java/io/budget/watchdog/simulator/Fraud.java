package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import java.time.Instant;
import java.util.List;
import java.util.random.RandomGenerator;

/** Injected suspicious charges: card testing, and charges from a country the card has never been used in. */
final class Fraud implements SpendingBehavior {

    private final SimulatorConfig config;

    Fraud(SimulatorConfig config) {
        this.config = config;
    }

    @Override
    public void planDay(DayPlan plan) {
        if (!config.scenariosActiveOn(plan.day())) {
            return;
        }
        RandomGenerator random = plan.user().random;
        if (Draw.chance(random, config.rates().cardTesting())) {
            cardTesting(plan);
        }
        if (Draw.chance(random, config.rates().newCountry())) {
            chargeFromANewCountry(plan);
        }
    }

    /** A stolen card number is tested with tiny charges at different merchants, then used for a big purchase. */
    private void cardTesting(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        Label label = Label.issue(IssueType.CARD_TESTING, user.newScenarioId());
        List<String> probes = Draw.pickDistinct(random, Merchants.CARD_TESTING_PROBES, Draw.between(random, 3, 5));
        Instant time = plan.at(Draw.timeBetween(random, 0, 22));
        for (String merchant : probes) {
            plan.add(time, merchant, Category.SHOPPING, Draw.centsBetween(random, 50, 200), SimulatedUser.HOME_COUNTRY, label);
            time = time.plusSeconds(Draw.between(random, 60, 8 * 60));
        }
        plan.add(time, Draw.pick(random, Merchants.CARD_TESTING_CASH_OUTS), Category.SHOPPING,
                Draw.centsBetween(random, 15_000, 90_000), SimulatedUser.HOME_COUNTRY, label);
    }

    private void chargeFromANewCountry(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        List<String> unused = Merchants.COUNTRIES.stream().filter(country -> !user.countriesUsed.contains(country)).toList();
        if (unused.isEmpty()) {
            return;
        }
        String country = Draw.pick(random, unused);
        user.countriesUsed.add(country);
        plan.add(plan.at(Draw.timeBetween(random, 0, 24)), Draw.pick(random, Merchants.FRAUD_ABROAD), Category.SHOPPING,
                Draw.centsBetween(random, 20_000, 150_000), country,
                Label.issue(IssueType.NEW_COUNTRY, user.newScenarioId()));
    }
}
