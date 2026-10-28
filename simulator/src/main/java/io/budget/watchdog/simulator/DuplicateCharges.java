package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Injected issue: a card purchase posts a second time a few seconds later, as when a payment terminal or processor
 * glitches. Runs after the other behaviors so it can pick from the whole day's purchases.
 */
final class DuplicateCharges implements SpendingBehavior {

    private static final Set<Category> CARD_PURCHASES =
            EnumSet.of(Category.GROCERIES, Category.DINING, Category.TRANSPORTATION, Category.SHOPPING);

    private final SimulatorConfig config;

    DuplicateCharges(SimulatorConfig config) {
        this.config = config;
    }

    @Override
    public void planDay(DayPlan plan) {
        if (!config.scenariosActiveOn(plan.day())) {
            return;
        }
        SimulatedUser user = plan.user();
        RandomGenerator random = user.random;
        for (PlannedTransaction original : List.copyOf(plan.transactions())) {
            boolean eligible = CARD_PURCHASES.contains(original.category()) && original.label().scenarioId() == null;
            if (!eligible || !Draw.chance(random, config.rates().duplicateCharge())) {
                continue;
            }
            Instant copyTime = original.occurredAt().plusSeconds(Draw.between(random, 5, 90));
            if (!plan.includes(copyTime)) {
                continue; // a copy after midnight would belong to tomorrow's plan
            }
            Label label = Label.issue(IssueType.DUPLICATE_CHARGE, user.newScenarioId());
            plan.relabel(original, Label.partOf(label.scenarioId()));
            plan.add(copyTime, original.merchant(), original.category(), original.amountCents(), original.country(), label);
        }
    }
}
