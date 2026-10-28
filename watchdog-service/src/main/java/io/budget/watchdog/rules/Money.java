package io.budget.watchdog.rules;

import java.util.Locale;

/** Formats cents for explanations. Integer math only, like everywhere else money is handled. */
final class Money {

    private Money() {
    }

    /** 129900 becomes "$1,299.00". */
    static String format(long cents) {
        return String.format(Locale.US, "$%,d.%02d", cents / 100, cents % 100);
    }
}
