package io.budget.watchdog.rules;

import java.time.Duration;
import java.time.Instant;

/**
 * "About a month apart, at a steady price": the check behind the price-hike rule, shared with the recurring charges
 * list so the two always agree on what counts as a monthly charge. Integer math only, like everywhere else money is
 * handled, so the edges are exact.
 */
public final class MonthlyPattern {

    private final Duration minGap;
    private final Duration maxGap;
    private final int tolerancePercent;

    public MonthlyPattern(Duration minGap, Duration maxGap, int tolerancePercent) {
        this.minGap = minGap;
        this.maxGap = maxGap;
        this.tolerancePercent = tolerancePercent;
    }

    /** The longest gap that still counts as monthly; anything quiet longer than this has stopped being monthly. */
    public Duration maxGap() {
        return maxGap;
    }

    public boolean isMonthly(Instant earlier, Instant later) {
        Duration gap = Duration.between(earlier, later);
        return gap.compareTo(minGap) >= 0 && gap.compareTo(maxGap) <= 0;
    }

    /** |b - a| / a <= tolerance, written as |b - a| * 100 <= a * tolerance so there's no rounding at the edge. */
    public boolean isWithinTolerance(long aCents, long bCents) {
        return Math.abs(bCents - aCents) * 100 <= aCents * tolerancePercent;
    }

    /** More than the tolerance above the previous price, so "steady" and "higher" can't both be true. */
    public boolean isHigher(long previousCents, long currentCents) {
        return currentCents * 100 > previousCents * (100 + tolerancePercent);
    }
}
