package io.budget.watchdog.api;

import io.budget.watchdog.rules.MonthlyPattern;
import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Finds a user's recurring charges in their recent transactions, with the price-hike rule's own idea of "monthly at a
 * steady price", so every price-hike alert has a matching row here. That includes rent and bills, not only
 * subscriptions, which is why they're called recurring charges. Nothing is stored: it's worked out on each request.
 */
@Component
class RecurringChargeFinder {

    enum Status {
        /** The last two charges were a month apart at a steady price. */
        ACTIVE,
        /**
         * Steady for two months, then a charge more than the tolerance higher: exactly what price-hike alerts on. After
         * two hikes in a row the months before aren't steady, so the merchant drops off until it's steady again.
         */
        PRICE_UP,
        /** No charge for longer than a month since the last one: cancelled, or missed. */
        OVERDUE
    }

    record RecurringCharge(String cardId, String merchant, Category category, long priceCents,
            long previousPriceCents, Status status, Instant lastChargedAt, Instant nextExpectedAt,
            long yearlyCostCents) {}

    private final MonthlyPattern monthly;

    RecurringChargeFinder(MonthlyPattern monthly) {
        this.monthly = monthly;
    }

    /** How far back to look: three monthly gaps, enough to see a steady pair and the charge after it. */
    Duration lookback() {
        return monthly.maxGap().multipliedBy(3);
    }

    /**
     * @param history the user's transactions in the lookback window, each card and merchant in event-time order
     * @param now     the user's newest transaction time (event time, never the wall clock)
     */
    List<RecurringCharge> find(List<Transaction> history, Instant now) {
        Map<String, List<Transaction>> byCardAndMerchant = new LinkedHashMap<>();
        for (Transaction tx : history) {
            if (tx.category() != Category.INCOME) {
                byCardAndMerchant.computeIfAbsent(tx.cardId() + "|" + tx.merchant(), key -> new ArrayList<>()).add(tx);
            }
        }

        List<RecurringCharge> found = new ArrayList<>();
        for (List<Transaction> charges : byCardAndMerchant.values()) {
            int n = charges.size();
            if (n < 2) {
                continue;
            }
            Transaction older = n >= 3 ? charges.get(n - 3) : null;
            Transaction previous = charges.get(n - 2);
            Transaction last = charges.get(n - 1);
            if (!monthly.isMonthly(previous.occurredAt(), last.occurredAt())) {
                continue;
            }

            Status status;
            if (monthly.isWithinTolerance(previous.amountCents(), last.amountCents())) {
                status = Status.ACTIVE;
            } else if (older != null && monthly.isMonthly(older.occurredAt(), previous.occurredAt())
                    && monthly.isWithinTolerance(older.amountCents(), previous.amountCents())
                    && monthly.isHigher(previous.amountCents(), last.amountCents())) {
                // The rule's definition, steady price included, so the list and the alerts agree. Dropping the steady
                // requirement kept back-to-back hikes listed but made every rising utility bill a "price hike".
                status = Status.PRICE_UP;
            } else {
                continue; // a monthly merchant with a price that moves around, like electricity
            }
            if (Duration.between(last.occurredAt(), now).compareTo(monthly.maxGap()) > 0) {
                status = Status.OVERDUE;
            }

            Duration gap = Duration.between(previous.occurredAt(), last.occurredAt());
            found.add(new RecurringCharge(last.cardId(), last.merchant(), last.category(), last.amountCents(),
                    previous.amountCents(), status, last.occurredAt(), last.occurredAt().plus(gap),
                    last.amountCents() * 12));
        }
        found.sort(Comparator.comparing(RecurringCharge::merchant).thenComparing(RecurringCharge::cardId));
        return found;
    }
}
