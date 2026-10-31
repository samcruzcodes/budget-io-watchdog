package io.budget.watchdog.api;

import io.budget.watchdog.alerts.AlertRepository;
import io.budget.watchdog.ingest.TransactionRepository;
import io.budget.watchdog.ingest.TransactionRepository.DayTotals;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/activity?days=}: transactions, spending, and alerts per simulated day, for the last {@code days}
 * days up to the newest stored transaction. "Today" is that transaction's UTC day, never the wall clock, because
 * simulated time runs years ahead. Every day in the range is listed, zeros included, so a chart has no gaps.
 */
@RestController
class ActivityController {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 365;

    record Day(LocalDate date, long transactions, long spendingCents, long alerts) {}

    /** {@code from} and {@code to} are null, and {@code days} empty, until a transaction is stored. */
    record Activity(LocalDate from, LocalDate to, List<Day> days) {}

    private final TransactionRepository transactions;
    private final AlertRepository alerts;

    ActivityController(TransactionRepository transactions, AlertRepository alerts) {
        this.transactions = transactions;
        this.alerts = alerts;
    }

    @GetMapping("/api/activity")
    Activity activity(@RequestParam(defaultValue = "" + DEFAULT_DAYS) int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw ApiParams.badRequest("days must be between 1 and " + MAX_DAYS + ", got " + days);
        }
        Optional<Instant> newest = transactions.newestOccurredAt();
        if (newest.isEmpty()) {
            return new Activity(null, null, List.of());
        }
        LocalDate to = LocalDate.ofInstant(newest.get(), ZoneOffset.UTC);
        LocalDate from = to.minusDays(days - 1);
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, DayTotals> totals = transactions.dailyTotals(start, end).stream()
                .collect(Collectors.toMap(DayTotals::day, Function.identity()));
        Map<LocalDate, Long> alertCounts = alerts.dailyCounts(start, end);
        List<Day> result = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DayTotals dayTotals = totals.get(day);
            result.add(new Day(day, dayTotals == null ? 0 : dayTotals.transactions(),
                    dayTotals == null ? 0 : dayTotals.spendingCents(), alertCounts.getOrDefault(day, 0L)));
        }
        return new Activity(from, to, result);
    }
}
