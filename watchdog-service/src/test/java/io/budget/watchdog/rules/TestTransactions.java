package io.budget.watchdog.rules;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.Transaction;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transactions for rule tests. Every field has a default, so a test only states what matters to it:
 * {@code charge().merchant("Netflix").cents(1549).at(START).build()}.
 */
final class TestTransactions {

    /** A Monday morning in UTC: the default time of a test's transactions. */
    static final Instant START = Instant.parse("2026-03-02T09:00:00Z");

    private static final AtomicLong ids = new AtomicLong();

    private TestTransactions() {
    }

    static Builder charge() {
        return new Builder();
    }

    static final class Builder {

        private String cardId = "card-0001";
        private String merchant = "Target";
        private Category category = Category.SHOPPING;
        private long amountCents = 2_500;
        private String country = "US";
        private Instant occurredAt = START;

        Builder card(String cardId) {
            this.cardId = cardId;
            return this;
        }

        Builder merchant(String merchant) {
            this.merchant = merchant;
            return this;
        }

        Builder category(Category category) {
            this.category = category;
            return this;
        }

        Builder cents(long amountCents) {
            this.amountCents = amountCents;
            return this;
        }

        Builder country(String country) {
            this.country = country;
            return this;
        }

        Builder at(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        Transaction build() {
            return new Transaction(Transaction.SCHEMA_VERSION, new UUID(0, ids.incrementAndGet()), "SIMULATOR",
                    cardId.replace("card", "user"), cardId, merchant, category, amountCents, "USD", country,
                    occurredAt, occurredAt);
        }
    }
}
