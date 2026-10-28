package io.budget.watchdog.rules;

/**
 * Mean and standard deviation of a stream of values without keeping the values (Welford's algorithm). It updates a
 * running mean and the sum of squared distances from it, which stays accurate for large values. The textbook shortcut
 * (sum of values and sum of squares) subtracts two huge, nearly equal numbers and can lose the answer entirely.
 */
final class RunningStats {

    private long count;
    private double mean;
    private double sumOfSquaredDistances; // "M2" in most write-ups of Welford's algorithm

    void add(double value) {
        count++;
        double delta = value - mean;      // distance from the old mean
        mean += delta / count;
        sumOfSquaredDistances += delta * (value - mean); // old distance times new distance
    }

    long count() {
        return count;
    }

    double mean() {
        return mean;
    }

    /** Sample standard deviation (divides by n - 1, like Python's statistics.stdev). 0 with fewer than two values. */
    double standardDeviation() {
        return count < 2 ? 0.0 : Math.sqrt(sumOfSquaredDistances / (count - 1));
    }
}
