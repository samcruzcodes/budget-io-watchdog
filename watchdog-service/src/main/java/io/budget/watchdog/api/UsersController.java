package io.budget.watchdog.api;

import io.budget.watchdog.ingest.TransactionRepository;
import io.budget.watchdog.ingest.TransactionRepository.UserSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/users}: the users that have transactions, so a client can offer a list instead of guessing IDs. */
@RestController
class UsersController {

    private final TransactionRepository transactions;

    UsersController(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @GetMapping("/api/users")
    List<UserSummary> users() {
        return transactions.users();
    }
}
