package io.budget.watchdog.simulator;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Everything that controls a simulation run (see Configuration in docs/DESIGN.md), read from environment variables.
 * The defaults work for a local run; config/simulator.demo.env is the preset that `docker compose up` uses.
 *
 * @param daysPerMinute        simulated days per real minute; {@code Double.POSITIVE_INFINITY} ("max") means no waiting
 * @param durationDays         how many simulated days to run; 0 runs until stopped
 * @param issuesStartAfterDays warm-up: no injected issues or look-alikes before this many simulated days, because
 *                             several rules need history before they can work
 */
record SimulatorConfig(
        long seed,
        int users,
        Map<Persona, Integer> personaMix,
        LocalDate startDate,
        double daysPerMinute,
        int durationDays,
        int issuesStartAfterDays,
        Rates rates,
        String kafkaBootstrapServers) {

    /** How often each scenario happens. Each rate is a probability; the comment gives what it applies to. */
    record Rates(
            double duplicateCharge,     // per card purchase
            double trialConversion,     // share of free trials the user forgets to cancel (the rest are cancelled)
            double priceHike,           // per subscription renewal
            double spendingSpike,       // per user-day
            double cardTesting,         // per user-day
            double newCountry,          // per user-day
            double doubleCoffee,        // per morning coffee
            double appStorePurchases,   // per user-day
            double bigPurchase,         // per user-day
            double trip,                // per user-day, times the persona's travel factor
            double tollRoadTrip,        // per user-day
            double foreignOnlineOrder)  // per user-day
    {
        /** Rare, roughly realistic rates. The demo preset turns them up so problems show up every few seconds. */
        static final Rates DEFAULTS = new Rates(
                0.0005, 0.4, 0.01, 0.002, 0.001, 0.001, 0.003, 0.002, 0.002, 0.003, 0.001, 0.001);
    }

    SimulatorConfig {
        if (users < 1) {
            throw new IllegalArgumentException("SIM_USERS must be at least 1");
        }
        if (personaMix.isEmpty() || personaMix.values().stream().anyMatch(weight -> weight < 1)) {
            throw new IllegalArgumentException("SIM_PERSONA_MIX needs at least one persona, each with a weight of 1 or more");
        }
        if (!(daysPerMinute > 0)) {
            throw new IllegalArgumentException("SIM_DAYS_PER_MINUTE must be positive, or 'max'");
        }
        if (durationDays < 0 || issuesStartAfterDays < 0) {
            throw new IllegalArgumentException("SIM_DURATION_DAYS and SIM_ISSUES_START_AFTER_DAYS can't be negative");
        }
        personaMix = Collections.unmodifiableMap(new LinkedHashMap<>(personaMix));
    }

    /** True once the warm-up is over and scenarios (issues and their look-alikes) may be injected. */
    boolean scenariosActiveOn(LocalDate day) {
        return !day.isBefore(startDate.plusDays(issuesStartAfterDays));
    }

    static SimulatorConfig fromEnvironment(Map<String, String> env) {
        Rates defaults = Rates.DEFAULTS;
        return new SimulatorConfig(
                Long.parseLong(get(env, "SIM_SEED", "42")),
                Integer.parseInt(get(env, "SIM_USERS", "3")),
                parsePersonaMix(get(env, "SIM_PERSONA_MIX", "STUDENT:1,FAMILY:1,FREQUENT_TRAVELER:1")),
                LocalDate.parse(get(env, "SIM_START_DATE", "2026-01-01")),
                parseSpeed(get(env, "SIM_DAYS_PER_MINUTE", "30")),
                Integer.parseInt(get(env, "SIM_DURATION_DAYS", "0")),
                Integer.parseInt(get(env, "SIM_ISSUES_START_AFTER_DAYS", "30")),
                new Rates(
                        rate(env, "SIM_RATE_DUPLICATE_CHARGE", defaults.duplicateCharge()),
                        rate(env, "SIM_RATE_TRIAL_CONVERSION", defaults.trialConversion()),
                        rate(env, "SIM_RATE_PRICE_HIKE", defaults.priceHike()),
                        rate(env, "SIM_RATE_SPENDING_SPIKE", defaults.spendingSpike()),
                        rate(env, "SIM_RATE_CARD_TESTING", defaults.cardTesting()),
                        rate(env, "SIM_RATE_NEW_COUNTRY", defaults.newCountry()),
                        rate(env, "SIM_RATE_DOUBLE_COFFEE", defaults.doubleCoffee()),
                        rate(env, "SIM_RATE_APP_STORE_PURCHASES", defaults.appStorePurchases()),
                        rate(env, "SIM_RATE_BIG_PURCHASE", defaults.bigPurchase()),
                        rate(env, "SIM_RATE_TRIP", defaults.trip()),
                        rate(env, "SIM_RATE_TOLL_ROAD_TRIP", defaults.tollRoadTrip()),
                        rate(env, "SIM_RATE_FOREIGN_ONLINE_ORDER", defaults.foreignOnlineOrder())),
                get(env, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"));
    }

    private static String get(Map<String, String> env, String name, String fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double rate(Map<String, String> env, String name, double fallback) {
        double value = Double.parseDouble(get(env, name, String.valueOf(fallback)));
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, was " + value);
        }
        return value;
    }

    private static double parseSpeed(String value) {
        return value.equalsIgnoreCase("max") ? Double.POSITIVE_INFINITY : Double.parseDouble(value);
    }

    /** Parses "STUDENT:25,FAMILY:30" into persona weights, keeping their order. */
    static Map<Persona, Integer> parsePersonaMix(String value) {
        Map<Persona, Integer> mix = new LinkedHashMap<>();
        for (String entry : value.split(",")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("SIM_PERSONA_MIX entries look like STUDENT:25, got '" + entry + "'");
            }
            mix.put(Persona.valueOf(parts[0].trim().toUpperCase(Locale.ROOT)), Integer.parseInt(parts[1].trim()));
        }
        return mix;
    }
}
