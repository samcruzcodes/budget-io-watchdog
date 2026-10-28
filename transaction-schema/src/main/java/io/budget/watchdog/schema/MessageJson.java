package io.budget.watchdog.schema;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place that defines how messages are written to and read from JSON. Every producer and
 * consumer uses it, so they can't disagree about the wire format, and the contract tests check
 * exactly what production runs.
 */
public final class MessageJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // Strict about what a message must contain. Jackson's defaults are lenient here:
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES) // every field present, as the JSON Schemas require
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)               // 15.49 must not become 15
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)                  // "1549" (a string) is not a number
            // Tolerant of fields it doesn't know, so a producer can add an optional field without breaking consumers.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Write fields in declaration order (Jackson 3 sorts alphabetically by default); easier to read in Kafka UI.
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    private MessageJson() {
    }

    public static String write(Object message) {
        return MAPPER.writeValueAsString(message);
    }

    /**
     * Parses a message and runs its constructor checks.
     *
     * @throws tools.jackson.core.JacksonException if the JSON is malformed or breaks the message contract
     */
    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
