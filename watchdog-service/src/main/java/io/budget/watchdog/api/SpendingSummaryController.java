package io.budget.watchdog.api;

import io.budget.watchdog.ingest.TransactionRepository;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/spending/summary}: a month of income and spending grouped by Budget.io line, so the planner could
 * fill itself in. Budget.io calls it from the browser, so it's the one endpoint with CORS (see {@link CorsConfig}).
 */
@RestController
class SpendingSummaryController {

    private final TransactionRepository transactions;

    SpendingSummaryController(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @GetMapping("/api/spending/summary")
    SpendingSummary summary(@RequestParam(required = false) String userId,
            @RequestParam(required = false) String month) {
        String user = ApiParams.required("userId", userId);
        YearMonth yearMonth = ApiParams.month(month);
        // Months are UTC, like the simulated people's days.
        return SpendingSummary.of(user, yearMonth, transactions.totalsByCategory(user,
                yearMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                yearMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
}
