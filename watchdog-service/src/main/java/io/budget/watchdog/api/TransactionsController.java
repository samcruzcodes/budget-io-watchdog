package io.budget.watchdog.api;

import io.budget.watchdog.ingest.TransactionRepository;
import io.budget.watchdog.schema.Transaction;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/transactions}: the newest stored transactions, by event time. */
@RestController
class TransactionsController {

    private final TransactionRepository transactions;

    TransactionsController(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @GetMapping("/api/transactions")
    List<Transaction> recent(@RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "" + ApiParams.DEFAULT_LIMIT) int limit) {
        return transactions.recent(ApiParams.optional(userId), ApiParams.limit(limit));
    }
}
