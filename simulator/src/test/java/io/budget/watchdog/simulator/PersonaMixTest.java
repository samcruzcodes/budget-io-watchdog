package io.budget.watchdog.simulator;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersonaMixTest {

    private static final Map<Persona, Integer> MIX = new LinkedHashMap<>();

    static {
        MIX.put(Persona.STUDENT, 25);
        MIX.put(Persona.YOUNG_PROFESSIONAL, 30);
        MIX.put(Persona.FAMILY, 25);
        MIX.put(Persona.FREQUENT_TRAVELER, 10);
        MIX.put(Persona.RETIREE, 10);
    }

    @Test
    void aThousandUsersAreSplitInExactProportion() {
        List<Persona> personas = PersonaMix.assign(MIX, 1_000);

        assertThat(personas.stream().collect(groupingBy(identity(), counting()))).isEqualTo(Map.of(
                Persona.STUDENT, 250L,
                Persona.YOUNG_PROFESSIONAL, 300L,
                Persona.FAMILY, 250L,
                Persona.FREQUENT_TRAVELER, 100L,
                Persona.RETIREE, 100L));
    }

    @Test
    void equalWeightsGiveOneUserEach() {
        Map<Persona, Integer> demo = new LinkedHashMap<>();
        demo.put(Persona.STUDENT, 1);
        demo.put(Persona.FAMILY, 1);
        demo.put(Persona.FREQUENT_TRAVELER, 1);

        assertThat(PersonaMix.assign(demo, 3))
                .containsExactly(Persona.STUDENT, Persona.FAMILY, Persona.FREQUENT_TRAVELER);
    }

    @Test
    void addingUsersKeepsEveryEarlierUsersPersona() {
        assertThat(PersonaMix.assign(MIX, 500).subList(0, 7)).isEqualTo(PersonaMix.assign(MIX, 7));
    }
}
