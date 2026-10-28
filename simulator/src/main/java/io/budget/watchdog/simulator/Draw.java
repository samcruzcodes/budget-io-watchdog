package io.budget.watchdog.simulator;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/** Random draws used by the simulator. Every draw comes from a user's own seeded generator, so runs are repeatable. */
final class Draw {

    private Draw() {
    }

    static boolean chance(RandomGenerator random, double probability) {
        return random.nextDouble() < probability;
    }

    /** How many times something happens today, for a habit that happens {@code perWeek} times a week on average. */
    static int countToday(RandomGenerator random, double perWeek) {
        return poisson(random, perWeek / 7);
    }

    /** A Poisson-distributed count (Knuth's method): the standard model for independent events at an average rate. */
    static int poisson(RandomGenerator random, double mean) {
        double limit = Math.exp(-mean);
        int count = 0;
        double product = random.nextDouble();
        while (product > limit) {
            count++;
            product *= random.nextDouble();
        }
        return count;
    }

    /**
     * An amount near {@code typicalCents}, drawn from a log-normal distribution: most purchases land close to the
     * typical amount and a few are much larger, the right-skewed shape real spending has. Never below 50 cents.
     */
    static long amountNear(RandomGenerator random, long typicalCents, double spread) {
        long cents = Math.round(typicalCents * Math.exp(spread * random.nextGaussian()));
        return Math.max(cents, 50);
    }

    /** A whole number from {@code min} to {@code max}, inclusive. */
    static int between(RandomGenerator random, int min, int max) {
        return random.nextInt(min, max + 1);
    }

    /** An amount in cents from {@code min} to {@code max}, inclusive. */
    static long centsBetween(RandomGenerator random, long min, long max) {
        return random.nextLong(min, max + 1);
    }

    /** A time of day, to the second, from {@code fromHour} (inclusive) to {@code toHour} (exclusive). */
    static LocalTime timeBetween(RandomGenerator random, int fromHour, int toHour) {
        return LocalTime.ofSecondOfDay(random.nextInt(fromHour * 3600, toHour * 3600));
    }

    static <T> T pick(RandomGenerator random, List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    /** Up to {@code count} different items, in random order. */
    static <T> List<T> pickDistinct(RandomGenerator random, List<T> items, int count) {
        List<T> shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, Math.min(count, shuffled.size())));
    }
}
