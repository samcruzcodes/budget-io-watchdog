package io.budget.watchdog.rules;

import java.time.Duration;

/** Short English for a time gap in explanations. */
final class Durations {

    private Durations() {
    }

    /** Seconds under two minutes ("1 second", "90 seconds"), whole minutes after that ("3 minutes"). */
    static String describe(Duration gap) {
        long seconds = gap.toSeconds();
        if (seconds < 120) {
            return seconds == 1 ? "1 second" : seconds + " seconds";
        }
        return gap.toMinutes() + " minutes";
    }

    /** Whole days, rounded down: "1 day", "12 days". */
    static String days(Duration duration) {
        long days = duration.toDays();
        return days == 1 ? "1 day" : days + " days";
    }
}
