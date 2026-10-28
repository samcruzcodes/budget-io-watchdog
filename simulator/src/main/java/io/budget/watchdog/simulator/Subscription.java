package io.budget.watchdog.simulator;

import java.time.LocalDate;
import java.util.UUID;

/** A monthly subscription. Its price can go up, and one that began as a forgotten free trial starts billing later. */
final class Subscription {

    final String merchant;
    final int billingDay;            // 1 to 28, so it exists in every month
    final LocalDate firstChargeDate;
    final UUID trialScenarioId;      // set when this subscription began as a free trial the user forgot to cancel
    long priceCents;
    boolean chargedBefore;

    private Subscription(String merchant, long priceCents, int billingDay, LocalDate firstChargeDate, UUID trialScenarioId) {
        this.merchant = merchant;
        this.priceCents = priceCents;
        this.billingDay = billingDay;
        this.firstChargeDate = firstChargeDate;
        this.trialScenarioId = trialScenarioId;
    }

    /** A subscription the user already has when the simulation starts; it bills on {@code billingDay} each month. */
    static Subscription existing(Merchants.Offer offer, int billingDay, LocalDate startDate) {
        LocalDate first = startDate.withDayOfMonth(billingDay);
        if (first.isBefore(startDate)) {
            first = first.plusMonths(1);
        }
        return new Subscription(offer.merchant(), offer.priceCents(), billingDay, first, null);
    }

    /** A free trial the user forgot to cancel: it charges the full price from {@code conversionDate} on. */
    static Subscription fromTrial(Merchants.Offer offer, LocalDate conversionDate, UUID scenarioId) {
        int billingDay = Math.min(conversionDate.getDayOfMonth(), 28);
        return new Subscription(offer.merchant(), offer.priceCents(), billingDay, conversionDate, scenarioId);
    }

    boolean isDueOn(LocalDate day) {
        return day.equals(firstChargeDate) || (day.isAfter(firstChargeDate) && day.getDayOfMonth() == billingDay);
    }

    /** True on the first full-price charge of a forgotten trial: the moment the trial "converts". */
    boolean isTrialConversion() {
        return trialScenarioId != null && !chargedBefore;
    }
}
