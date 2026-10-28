package io.budget.watchdog.simulator;

import io.budget.watchdog.schema.Category;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One user's transactions for one simulated day. Behaviors add to it in turn. */
final class DayPlan {

    private final SimulatedUser user;
    private final LocalDate day;
    private final List<PlannedTransaction> transactions = new ArrayList<>();

    DayPlan(SimulatedUser user, LocalDate day) {
        this.user = user;
        this.day = day;
    }

    SimulatedUser user() {
        return user;
    }

    LocalDate day() {
        return day;
    }

    /** A moment on this day. Simulated people live in UTC, which keeps time zones out of the picture. */
    Instant at(LocalTime time) {
        return day.atTime(time).toInstant(ZoneOffset.UTC);
    }

    boolean includes(Instant moment) {
        return moment.atZone(ZoneOffset.UTC).toLocalDate().equals(day);
    }

    /** Adds an ordinary transaction in the user's home country. */
    PlannedTransaction add(Instant occurredAt, String merchant, Category category, long amountCents) {
        return add(occurredAt, merchant, category, amountCents, SimulatedUser.HOME_COUNTRY, Label.NORMAL);
    }

    PlannedTransaction add(Instant occurredAt, String merchant, Category category, long amountCents, String country,
            Label label) {
        PlannedTransaction transaction = new PlannedTransaction(user.nextTransactionId(), user.userId, user.cardId,
                user.persona, occurredAt, merchant, category, amountCents, country, label);
        transactions.add(transaction);
        return transaction;
    }

    /** Moves the charges scheduled for today (trip spending, a follow-up App Store purchase) into the plan. */
    void addScheduledCharges() {
        for (ScheduledCharge charge : user.takeScheduledFor(day)) {
            add(at(charge.time()), charge.merchant(), charge.category(), charge.amountCents(), charge.country(),
                    charge.label());
        }
    }

    void relabel(PlannedTransaction transaction, Label label) {
        transactions.set(transactions.indexOf(transaction), transaction.withLabel(label));
    }

    /** Relabels every transaction in {@code category} so far today, for example all of a binge day's dining. */
    void relabelCategory(Category category, Label label) {
        transactions.replaceAll(transaction -> transaction.category() == category ? transaction.withLabel(label) : transaction);
    }

    /** True if a scenario already claimed a transaction in this category today. Keeps scenarios from overlapping. */
    boolean hasScenarioIn(Category category) {
        return transactions.stream()
                .anyMatch(transaction -> transaction.category() == category && transaction.label().scenarioId() != null);
    }

    List<PlannedTransaction> transactions() {
        return Collections.unmodifiableList(transactions);
    }
}
