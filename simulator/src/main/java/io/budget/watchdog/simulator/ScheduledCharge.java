package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import java.time.LocalDate;
import java.time.LocalTime;

/** A charge decided now that happens on a later day, such as spending during a booked trip. */
record ScheduledCharge(
        LocalDate date,
        LocalTime time,
        String merchant,
        Category category,
        long amountCents,
        String country,
        Label label) {
}
