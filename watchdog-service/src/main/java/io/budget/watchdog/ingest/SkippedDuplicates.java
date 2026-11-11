package io.budget.watchdog.ingest;

import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Counts the messages the listener skipped because their transaction was already stored. Throughput only counts new
 * rows, so without this number a watchdog that's busy skipping re-sent messages looks exactly like an idle one: that
 * happened when the simulator was restarted and published its whole run again.
 *
 * <p>The count lives in this process's memory, in one bucket per second for the last minute. It starts at zero when
 * the watchdog starts, and with several watchdogs each one reports its own.
 */
@Component
public class SkippedDuplicates {

    static final int WINDOW_SECONDS = 60;

    /** Skipped in the last {@code windowSeconds} on the real clock, and since this watchdog started. */
    public record Recent(long windowSeconds, long skipped, long sinceStartup) {}

    private final Clock clock;
    // Bucket i holds the count for one second of real time: the second whose number, divided by 60, leaves i.
    private final long[] bucketSecond = new long[WINDOW_SECONDS];
    private final long[] bucketCount = new long[WINDOW_SECONDS];
    private long sinceStartup;

    SkippedDuplicates(Clock clock) {
        this.clock = clock;
    }

    /** Called by the listener's threads, once per skipped message. */
    synchronized void record() {
        long second = clock.instant().getEpochSecond();
        int bucket = (int) (second % WINDOW_SECONDS);
        if (bucketSecond[bucket] != second) { // the bucket still holds a count from a minute or more ago
            bucketSecond[bucket] = second;
            bucketCount[bucket] = 0;
        }
        bucketCount[bucket]++;
        sinceStartup++;
    }

    public synchronized Recent recent() {
        long now = clock.instant().getEpochSecond();
        long skipped = 0;
        for (int bucket = 0; bucket < WINDOW_SECONDS; bucket++) {
            if (now - bucketSecond[bucket] < WINDOW_SECONDS) {
                skipped += bucketCount[bucket];
            }
        }
        return new Recent(WINDOW_SECONDS, skipped, sinceStartup);
    }
}
