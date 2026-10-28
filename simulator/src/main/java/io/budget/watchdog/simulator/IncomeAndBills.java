package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import java.time.LocalDate;
import java.time.LocalTime;

/** Paychecks, rent or mortgage, and utility bills (electricity and water vary month to month). */
final class IncomeAndBills implements SpendingBehavior {

    @Override
    public void planDay(DayPlan plan) {
        SimulatedUser user = plan.user();
        LocalDate day = plan.day();
        Persona persona = user.persona;

        for (int earner = 0; earner < user.employers.size(); earner++) {
            if (isPayday(user, earner, day)) {
                plan.add(plan.at(LocalTime.of(6, 0)), user.employers.get(earner), Category.INCOME,
                        persona.income.paycheckCents());
            }
        }
        if (day.getDayOfMonth() == 1) {
            String payee = persona.housing.mortgage() ? Merchants.MORTGAGE_LENDER : Merchants.LANDLORD;
            plan.add(plan.at(LocalTime.of(9, 0)), payee, Category.RENT, persona.housing.monthlyCents());
        }
        for (SimulatedUser.Utility utility : user.utilities) {
            if (day.getDayOfMonth() == utility.billingDay()) {
                long amount = utility.varies()
                        ? Draw.amountNear(user.random, utility.typicalCents(), 0.2)
                        : utility.typicalCents();
                plan.add(plan.at(LocalTime.of(8, 0)), utility.merchant(), Category.UTILITIES, amount);
            }
        }
    }

    /** Biweekly pay arrives every 14 days (a second earner on the alternate week); monthly income on the 1st. */
    private static boolean isPayday(SimulatedUser user, int earner, LocalDate day) {
        return switch (user.persona.income.schedule()) {
            case MONTHLY -> day.getDayOfMonth() == 1;
            case BIWEEKLY -> Math.floorMod(day.toEpochDay() + user.payCycleOffset + 7L * earner, 14) == 0;
        };
    }
}
