package io.budget.watchdog.schema;

/** Small checks shared by the message records' constructors. */
final class Validation {

    private Validation() {
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
