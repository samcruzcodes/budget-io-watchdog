package io.budget.watchdog.rules;

import java.time.Duration;

/**
 * Checks for the rules' Settings records. A bad or missing value in application.yml stops startup with a message that
 * names the setting. A missing number binds as 0, so "must be positive" catches that too.
 */
final class Check {

    private Check() {
    }

    static void positive(String setting, Duration value) {
        require(value != null && value.isPositive(), setting + " must be positive, got " + value);
    }

    static void positive(String setting, long value) {
        require(value > 0, setting + " must be positive, got " + value);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
