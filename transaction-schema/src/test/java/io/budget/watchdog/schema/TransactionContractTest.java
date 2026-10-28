package io.budget.watchdog.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Transaction record (read through MessageJson) and transaction.v1.schema.json are two descriptions of
 * one contract. These tests give both the same messages and check that they agree.
 */
class TransactionContractTest {

    private static final Schema SCHEMA = ContractSchemas.load("/schemas/transaction.v1.schema.json");
    private static final JsonMapper TREE = JsonMapper.builder().build();

    private static final String VALID = """
            {
              "schemaVersion": 1,
              "transactionId": "0f9d8a52-3c1e-3b7a-9e64-2d5f1c8b7a10",
              "source": "SIMULATOR",
              "userId": "user-0001",
              "cardId": "card-0001",
              "merchant": "Netflix",
              "category": "SUBSCRIPTIONS",
              "amountCents": 1549,
              "currency": "USD",
              "country": "US",
              "occurredAt": "2026-03-04T19:12:07Z",
              "publishedAt": "2026-10-27T21:40:01.123Z"
            }
            """;

    @Test
    void validMessageIsAcceptedByTheRecordAndTheSchema() {
        Transaction tx = MessageJson.read(VALID, Transaction.class);

        assertThat(tx.merchant()).isEqualTo("Netflix");
        assertThat(tx.category()).isEqualTo(Category.SUBSCRIPTIONS);
        assertThat(tx.amountCents()).isEqualTo(1549);
        assertThat(tx.occurredAt()).isEqualTo(Instant.parse("2026-03-04T19:12:07Z"));
        assertThat(SCHEMA.validate(VALID, InputFormat.JSON)).isEmpty();
    }

    @Test
    void writingThenReadingGivesBackTheSameTransaction() {
        Transaction original = MessageJson.read(VALID, Transaction.class);

        String json = MessageJson.write(original);

        assertThat(MessageJson.read(json, Transaction.class)).isEqualTo(original);
        assertThat(SCHEMA.validate(json, InputFormat.JSON)).isEmpty();
        assertThat(json).contains("\"occurredAt\":\"2026-03-04T19:12:07Z\""); // ISO-8601 text, not a number
    }

    @Test
    void unknownFieldsAreIgnoredSoProducersCanAddOptionalFields() {
        String withExtraField = with("loyaltyPoints", 12);

        assertThat(MessageJson.read(withExtraField, Transaction.class))
                .isEqualTo(MessageJson.read(VALID, Transaction.class));
        assertThat(SCHEMA.validate(withExtraField, InputFormat.JSON)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    void invalidMessageIsRejectedByTheRecordAndTheSchema(String problem, String json) {
        assertThatThrownBy(() -> MessageJson.read(json, Transaction.class)).isInstanceOf(JacksonException.class);
        assertThat(SCHEMA.validate(json, InputFormat.JSON)).isNotEmpty();
    }

    static Stream<Arguments> invalidMessages() {
        return Stream.of(
                arguments("negative amount", with("amountCents", -1)),
                arguments("fractional amount", with("amountCents", 15.49)),
                arguments("amount written as a string", with("amountCents", "1549")),
                arguments("null amount", with("amountCents", null)),
                arguments("missing merchant", without("merchant")),
                arguments("blank merchant", with("merchant", "   ")),
                arguments("lowercase currency", with("currency", "usd")),
                arguments("three-letter country", with("country", "USA")),
                arguments("unknown category", with("category", "PIZZA")),
                arguments("unsupported schema version", with("schemaVersion", 2)),
                arguments("malformed transaction ID", with("transactionId", "not-a-uuid")),
                arguments("malformed timestamp", with("occurredAt", "yesterday")),
                arguments("null timestamp", with("publishedAt", null)));
    }

    /** VALID with one field replaced. */
    private static String with(String field, Object value) {
        ObjectNode message = (ObjectNode) TREE.readTree(VALID);
        if (value == null) {
            message.putNull(field);
        } else {
            message.set(field, TREE.valueToTree(value));
        }
        return TREE.writeValueAsString(message);
    }

    /** VALID with one field removed. */
    private static String without(String field) {
        ObjectNode message = (ObjectNode) TREE.readTree(VALID);
        message.remove(field);
        return TREE.writeValueAsString(message);
    }
}
