package io.budget.watchdog.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.Transaction;
import org.junit.jupiter.api.Test;

class TransactionParserTest {

    private static final String VALID = """
            {"schemaVersion": 1, "transactionId": "0f9d8a52-3c1e-3b7a-9e64-2d5f1c8b7a10", "source": "SIMULATOR",
             "userId": "user-0001", "cardId": "card-0001", "merchant": "Netflix", "category": "SUBSCRIPTIONS",
             "amountCents": 1549, "currency": "USD", "country": "US",
             "occurredAt": "2026-03-04T19:12:07Z", "publishedAt": "2026-10-27T21:40:01.123Z"}
            """;

    private final TransactionParser parser = new TransactionParser();

    @Test
    void parsesAValidMessage() {
        Transaction tx = parser.parse(VALID);

        assertThat(tx.cardId()).isEqualTo("card-0001");
        assertThat(tx.category()).isEqualTo(Category.SUBSCRIPTIONS);
        assertThat(tx.amountCents()).isEqualTo(1549);
    }

    @Test
    void ignoresFieldsItDoesntKnow() {
        assertThat(parser.parse(VALID.replace("\"source\"", "\"loyaltyPoints\": 12, \"source\"")))
                .isEqualTo(parser.parse(VALID));
    }

    @Test
    void contractViolationBecomesInvalidTransactionWithTheReason() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("1549", "-1549")))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("amountCents must not be negative");
    }

    @Test
    void textThatIsntJsonBecomesInvalidTransaction() {
        assertThatThrownBy(() -> parser.parse("hello")).isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void emptyMessageBecomesInvalidTransaction() {
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(InvalidMessageException.class);
    }
}
