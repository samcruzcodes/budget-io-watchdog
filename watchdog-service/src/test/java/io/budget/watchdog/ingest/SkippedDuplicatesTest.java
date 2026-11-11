package io.budget.watchdog.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SkippedDuplicatesTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-11T12:00:00Z"));
    private final SkippedDuplicates duplicates = new SkippedDuplicates(clock);

    @Test
    void startsAtZero() {
        assertThat(duplicates.recent()).isEqualTo(new SkippedDuplicates.Recent(60, 0, 0));
    }

    @Test
    void countsTheLastMinuteAndEverythingSinceStartup() {
        duplicates.record();
        duplicates.record();
        clock.advance(Duration.ofSeconds(30));
        duplicates.record();

        assertThat(duplicates.recent()).isEqualTo(new SkippedDuplicates.Recent(60, 3, 3));

        // 59 seconds after the first two, they still count; one second later only the third does.
        clock.advance(Duration.ofSeconds(29));
        assertThat(duplicates.recent().skipped()).isEqualTo(3);
        clock.advance(Duration.ofSeconds(1));
        assertThat(duplicates.recent()).isEqualTo(new SkippedDuplicates.Recent(60, 1, 3));
    }

    @Test
    void anOldBucketIsEmptiedBeforeItIsUsedAgain() {
        duplicates.record();
        clock.advance(Duration.ofSeconds(60)); // the same bucket, a minute later
        duplicates.record();

        assertThat(duplicates.recent()).isEqualTo(new SkippedDuplicates.Recent(60, 1, 2));
    }

    @Test
    void goesBackToZeroOnceTheReplayStops() {
        for (int i = 0; i < 500; i++) {
            duplicates.record();
            clock.advance(Duration.ofMillis(100));
        }
        assertThat(duplicates.recent().skipped()).isPositive();

        clock.advance(Duration.ofMinutes(2));
        assertThat(duplicates.recent()).isEqualTo(new SkippedDuplicates.Recent(60, 0, 500));
    }

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
