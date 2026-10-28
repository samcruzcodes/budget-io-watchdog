package io.budget.watchdog.simulator;

/**
 * Spending profiles (see Personas in docs/DESIGN.md). Each persona is a row of numbers describing a kind of person:
 * how they're paid, what they spend on, and how often. Amounts are typical values; actual purchases vary around them.
 */
enum Persona {

    STUDENT(
            new Income(PaySchedule.BIWEEKLY, 1, dollars(620)),
            new Housing(false, dollars(750)),   // a shared apartment
            0.6,                                // utilities, relative to an average household
            new Habit(1.5, dollars(45)),        // groceries: trips per week, typical trip
            new Habit(5, dollars(16)),          // dining out and delivery
            0.7,                                // chance of a morning coffee
            false,                              // owns a car (gas) or not (rideshare)
            new Habit(3, dollars(14)),          // transportation
            new Habit(1, dollars(35)),          // shopping
            6,                                  // subscriptions
            1.0,                                // free trials started per month
            0.5),                               // trips, relative to an average person

    YOUNG_PROFESSIONAL(
            new Income(PaySchedule.BIWEEKLY, 1, dollars(2_250)),
            new Housing(false, dollars(1_850)),
            0.9,
            new Habit(1.5, dollars(70)),
            new Habit(4, dollars(28)),
            0.6,
            false,
            new Habit(3, dollars(18)),
            new Habit(1.5, dollars(55)),
            5,
            0.5,
            1.0),

    FAMILY(
            new Income(PaySchedule.BIWEEKLY, 2, dollars(2_600)),
            new Housing(true, dollars(2_400)),
            1.5,
            new Habit(2, dollars(160)),
            new Habit(2, dollars(55)),
            0.3,
            true,
            new Habit(1.5, dollars(55)),
            new Habit(2, dollars(70)),
            5,
            0.3,
            0.8),

    FREQUENT_TRAVELER(
            new Income(PaySchedule.BIWEEKLY, 1, dollars(4_300)),
            new Housing(false, dollars(2_600)),
            1.0,
            new Habit(1, dollars(80)),
            new Habit(6, dollars(38)),
            0.6,
            false,
            new Habit(4, dollars(24)),
            new Habit(1.5, dollars(90)),
            4,
            0.3,
            5.0),

    RETIREE(
            new Income(PaySchedule.MONTHLY, 1, dollars(3_200)),
            new Housing(true, dollars(1_100)),
            1.1,
            new Habit(1.5, dollars(65)),
            new Habit(1.5, dollars(30)),
            0.4,
            true,
            new Habit(0.7, dollars(45)),
            new Habit(0.8, dollars(50)),
            3,
            0.1,
            1.0);

    enum PaySchedule { BIWEEKLY, MONTHLY }

    record Income(PaySchedule schedule, int earners, long paycheckCents) {}

    record Housing(boolean mortgage, long monthlyCents) {}

    /** Something done about {@code perWeek} times a week, costing around {@code typicalCents} each time. */
    record Habit(double perWeek, long typicalCents) {}

    final Income income;
    final Housing housing;
    final double utilitiesFactor;
    final Habit groceries;
    final Habit dining;
    final double coffeeChance;
    final boolean ownsCar;
    final Habit transportation;
    final Habit shopping;
    final int subscriptions;
    final double trialsPerMonth;
    final double travelFactor;

    Persona(Income income, Housing housing, double utilitiesFactor, Habit groceries, Habit dining, double coffeeChance,
            boolean ownsCar, Habit transportation, Habit shopping, int subscriptions, double trialsPerMonth,
            double travelFactor) {
        this.income = income;
        this.housing = housing;
        this.utilitiesFactor = utilitiesFactor;
        this.groceries = groceries;
        this.dining = dining;
        this.coffeeChance = coffeeChance;
        this.ownsCar = ownsCar;
        this.transportation = transportation;
        this.shopping = shopping;
        this.subscriptions = subscriptions;
        this.trialsPerMonth = trialsPerMonth;
        this.travelFactor = travelFactor;
    }

    private static long dollars(int amount) {
        return amount * 100L;
    }
}
