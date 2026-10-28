package io.budget.watchdog.simulator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/** The simulated world: the users, the behaviors that make them spend, and how one day is planned. */
final class Simulation {

    private final List<SimulatedUser> users;
    private final List<SpendingBehavior> behaviors;

    Simulation(List<SimulatedUser> users, List<SpendingBehavior> behaviors) {
        this.users = List.copyOf(users);
        this.behaviors = List.copyOf(behaviors);
    }

    static Simulation create(SimulatorConfig config) {
        List<Persona> personas = PersonaMix.assign(config.personaMix(), config.users());
        // split() hands each user an independent random stream. Users are created in order, so user N always gets the
        // N-th split, and adding users never changes earlier users. (Deriving seeds by hand, like seed + user number,
        // can give users overlapping streams; SimulationTest's rate checks caught exactly that.)
        SplittableRandom root = new SplittableRandom(config.seed());
        List<SimulatedUser> users = new ArrayList<>();
        for (int i = 0; i < personas.size(); i++) {
            users.add(new SimulatedUser(i + 1, personas.get(i), config.seed(), root.split(), config.startDate()));
        }
        // Order matters: DuplicateCharges runs last so it can pick from the whole day's purchases.
        List<SpendingBehavior> behaviors = List.of(
                new IncomeAndBills(),
                new Subscriptions(config),
                new EverydaySpending(config),
                new Travel(config),
                new Fraud(config),
                new DuplicateCharges(config));
        return new Simulation(users, behaviors);
    }

    /** Every user's transactions for one simulated day, sorted by time. */
    List<PlannedTransaction> planDay(LocalDate day) {
        List<PlannedTransaction> all = new ArrayList<>();
        for (SimulatedUser user : users) {
            DayPlan plan = new DayPlan(user, day);
            plan.addScheduledCharges();
            for (SpendingBehavior behavior : behaviors) {
                behavior.planDay(plan);
            }
            all.addAll(plan.transactions());
        }
        all.sort(Comparator.comparing(PlannedTransaction::occurredAt)); // a stable sort, so ties keep a fixed order
        return all;
    }
}
