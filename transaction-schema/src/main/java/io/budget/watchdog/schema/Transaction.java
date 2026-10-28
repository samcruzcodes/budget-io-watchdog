package io.budget.watchdog.schema;

import static io.budget.watchdog.schema.Validation.hasText;
import static io.budget.watchdog.schema.Validation.require;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Transaction schema v1: what every source publishes to the {@code transactions} topic, keyed by cardId.
 *
 * <p>The compact constructor enforces the contract. It runs on every creation, including when Jackson
 * parses JSON, so an invalid Transaction can't exist: a producer can't build one and a consumer can't
 * parse one. Field meanings are in docs/DESIGN.md, under The transaction message.
 */
public record Transaction(
        int schemaVersion,
        UUID transactionId,
        String source,
        String userId,
        String cardId,
        String merchant,
        Category category,
        long amountCents,
        String currency,
        String country,
        Instant occurredAt,
        Instant publishedAt) {

    public static final int SCHEMA_VERSION = 1;

    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}"); // ISO 4217, e.g. USD
    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");  // ISO 3166-1 alpha-2, e.g. US

    public Transaction {
        require(schemaVersion == SCHEMA_VERSION, "unsupported schemaVersion: " + schemaVersion);
        require(transactionId != null, "transactionId is required");
        require(hasText(source), "source is required");
        require(hasText(userId), "userId is required");
        require(hasText(cardId), "cardId is required");
        require(hasText(merchant), "merchant is required");
        require(category != null, "category is required");
        require(amountCents >= 0, "amountCents must not be negative");
        require(currency != null && CURRENCY_CODE.matcher(currency).matches(), "currency must be a 3-letter code like USD");
        require(country != null && COUNTRY_CODE.matcher(country).matches(), "country must be a 2-letter code like US");
        require(occurredAt != null, "occurredAt is required");
        require(publishedAt != null, "publishedAt is required");
    }
}
