package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.EdgeCase;
import io.budget.watchdog.schema.IssueType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Monthly subscriptions with occasional price hikes (an injected issue), free trials that convert when the user
 * forgets to cancel (an injected issue), and App Store purchases that look like a converting trial (a look-alike).
 */
final class Subscriptions implements SpendingBehavior {

    private final SimulatorConfig config;

    Subscriptions(SimulatorConfig config) {
        this.config = config;
    }

    @Override
    public void planDay(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        boolean scenariosActive = config.scenariosActiveOn(plan.day());

        for (Subscription subscription : user.subscriptions) {
            if (!subscription.isDueOn(plan.day())) {
                continue;
            }
            Label label = Label.NORMAL;
            if (subscription.isTrialConversion()) {
                label = Label.issue(IssueType.TRIAL_CONVERSION, subscription.trialScenarioId);
            } else if (scenariosActive && subscription.chargedBefore && Draw.chance(random, config.rates().priceHike())) {
                subscription.priceCents = raisedPrice(subscription.priceCents, random);
                label = Label.issue(IssueType.PRICE_HIKE, user.newScenarioId());
            }
            plan.add(plan.at(Draw.timeBetween(random, 3, 8)), subscription.merchant, Category.SUBSCRIPTIONS,
                    subscription.priceCents, SimulatedUser.HOME_COUNTRY, label);
            subscription.chargedBefore = true;
        }

        if (scenariosActive) {
            if (Draw.chance(random, user.persona.trialsPerMonth / 30)) {
                startFreeTrial(plan);
            }
            if (Draw.chance(random, config.rates().appStorePurchases())) {
                appStorePurchases(plan);
            }
        }
    }

    /** A 10–25% increase, rounded to end in .99 like a real price list, and always at least $1 more. */
    static long raisedPrice(long cents, RandomGenerator random) {
        double factor = 1.10 + random.nextDouble() * 0.15;
        long raised = Math.round(cents * factor / 100.0) * 100 - 1;
        return Math.max(raised, cents + 100);
    }

    private void startFreeTrial(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        List<Merchants.Offer> untried = Merchants.TRIAL_OFFERS.stream()
                .filter(offer -> !user.triedTrials.contains(offer.merchant()))
                .toList();
        if (untried.isEmpty()) {
            return;
        }
        Merchants.Offer offer = Draw.pick(random, untried);
        user.triedTrials.add(offer.merchant());
        UUID scenario = user.newScenarioId();
        long signupCents = random.nextBoolean() ? 0 : 100; // $0, or a $1 card check
        Instant signup = plan.at(Draw.timeBetween(random, 12, 23));

        if (Draw.chance(random, config.rates().trialConversion())) {
            // Injected issue: the user forgets to cancel, and 7–30 days later the trial becomes a paid plan.
            plan.add(signup, offer.merchant(), Category.SUBSCRIPTIONS, signupCents, SimulatedUser.HOME_COUNTRY,
                    Label.partOf(scenario));
            user.subscriptions.add(Subscription.fromTrial(offer, plan.day().plusDays(Draw.between(random, 7, 30)), scenario));
        } else {
            plan.add(signup, offer.merchant(), Category.SUBSCRIPTIONS, signupCents, SimulatedUser.HOME_COUNTRY,
                    Label.edgeCase(EdgeCase.CANCELLED_TRIAL, scenario));
        }
    }

    /** Look-alike: $0.99, then $9.99 at the App Store days later. Shaped like a trial converting, but legitimate. */
    private void appStorePurchases(DayPlan plan) {
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        Label label = Label.edgeCase(EdgeCase.APP_STORE_PURCHASES, user.newScenarioId());
        plan.add(plan.at(Draw.timeBetween(random, 8, 23)), Merchants.APP_STORE, Category.SHOPPING, 99,
                SimulatedUser.HOME_COUNTRY, label);
        user.schedule(new ScheduledCharge(plan.day().plusDays(Draw.between(random, 5, 20)), Draw.timeBetween(random, 8, 23),
                Merchants.APP_STORE, Category.SHOPPING, 999, SimulatedUser.HOME_COUNTRY, label));
    }
}
