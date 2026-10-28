package io.budget.watchdog.simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns personas to users in exact proportion to their weights, using smooth weighted round-robin: each turn, every
 * persona earns credit equal to its weight; the persona with the most credit gets the next user and pays back the
 * total weight. With weights 25/30/25/10/10, every block of 100 users holds exactly 25/30/25/10/10, and user N's
 * persona doesn't depend on how many users there are in total.
 */
final class PersonaMix {

    private PersonaMix() {
    }

    static List<Persona> assign(Map<Persona, Integer> weights, int users) {
        int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
        Map<Persona, Integer> credit = new LinkedHashMap<>();
        weights.keySet().forEach(persona -> credit.put(persona, 0));

        List<Persona> assigned = new ArrayList<>(users);
        for (int user = 0; user < users; user++) {
            Persona next = null;
            for (Map.Entry<Persona, Integer> entry : weights.entrySet()) {
                Persona persona = entry.getKey();
                credit.merge(persona, entry.getValue(), Integer::sum);
                if (next == null || credit.get(persona) > credit.get(next)) {
                    next = persona; // ties go to the persona listed first
                }
            }
            credit.merge(next, -totalWeight, Integer::sum);
            assigned.add(next);
        }
        return assigned;
    }
}
