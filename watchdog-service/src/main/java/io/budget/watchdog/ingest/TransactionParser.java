package io.budget.watchdog.ingest;

import io.budget.watchdog.schema.MessageJson;
import io.budget.watchdog.schema.Transaction;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/** Turns a Kafka message value into a Transaction, or explains why it can't. */
@Component
public class TransactionParser {

    /** @throws InvalidMessageException if the message isn't a valid Transaction v1 */
    public Transaction parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidMessageException("empty message", null);
        }
        try {
            return MessageJson.read(json, Transaction.class);
        } catch (JacksonException e) {
            // Covers malformed JSON and broken contract rules alike (Transaction's constructor runs during parsing).
            throw new InvalidMessageException("not a valid Transaction v1: " + e.getOriginalMessage(), e);
        }
    }
}
