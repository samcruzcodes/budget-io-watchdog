package io.budget.watchdog.api;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checks for query parameters. A bad one becomes a 400 with Spring's standard problem JSON, which says what was wrong.
 * (A {@code limit} that isn't a number, or a missing required parameter, Spring turns into a 400 by itself.)
 */
final class ApiParams {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;

    private ApiParams() {
    }

    static int limit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw badRequest("limit must be between 1 and " + MAX_LIMIT + ", got " + limit);
        }
        return limit;
    }

    /** A filter that's optional: absent or blank means "any". */
    static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw badRequest(name + " is required");
        }
        return value;
    }

    static YearMonth month(String value) {
        try {
            return YearMonth.parse(required("month", value)); // "2026-03"
        } catch (DateTimeParseException e) {
            throw badRequest("month must look like 2026-03, got " + value);
        }
    }

    static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
