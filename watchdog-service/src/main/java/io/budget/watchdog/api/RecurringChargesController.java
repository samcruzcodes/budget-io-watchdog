package io.budget.watchdog.api;

import io.budget.watchdog.api.RecurringChargeFinder.RecurringCharge;
import io.budget.watchdog.ingest.TransactionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/recurring}: a user's charges that recur monthly at a steady price, worked out on request. */
@RestController
class RecurringChargesController {

    private final TransactionRepository transactions;
    private final RecurringChargeFinder finder;

    RecurringChargesController(TransactionRepository transactions, RecurringChargeFinder finder) {
        this.transactions = transactions;
        this.finder = finder;
    }

    @GetMapping("/api/recurring")
    List<RecurringCharge> recurring(@RequestParam(required = false) String userId) {
        String user = ApiParams.required("userId", userId);
        // "Now" is the user's newest transaction, not the wall clock: simulated time runs years ahead of real time.
        Optional<Instant> now = transactions.newestOccurredAtFor(user);
        if (now.isEmpty()) {
            return List.of();
        }
        return finder.find(transactions.forUserSince(user, now.get().minus(finder.lookback())), now.get());
    }
}
