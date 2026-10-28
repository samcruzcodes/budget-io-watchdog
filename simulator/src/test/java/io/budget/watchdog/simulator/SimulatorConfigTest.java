package io.budget.watchdog.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimulatorConfigTest {

    @Test
    void defaultsWorkForALocalRun() {
        SimulatorConfig config = SimulatorConfig.fromEnvironment(Map.of());

        assertThat(config.users()).isEqualTo(3);
        assertThat(config.personaMix()).containsOnlyKeys(Persona.STUDENT, Persona.FAMILY, Persona.FREQUENT_TRAVELER);
        assertThat(config.daysPerMinute()).isEqualTo(30);
        assertThat(config.durationDays()).isZero();
        assertThat(config.rates()).isEqualTo(SimulatorConfig.Rates.DEFAULTS);
        assertThat(config.kafkaBootstrapServers()).isEqualTo("localhost:9094");
    }

    @Test
    void readsEnvironmentVariables() {
        SimulatorConfig config = SimulatorConfig.fromEnvironment(Map.of(
                "SIM_USERS", "1000",
                "SIM_DAYS_PER_MINUTE", "max",
                "SIM_PERSONA_MIX", "student:25, retiree:75",
                "SIM_RATE_CARD_TESTING", "0.5"));

        assertThat(config.users()).isEqualTo(1000);
        assertThat(config.daysPerMinute()).isInfinite();
        assertThat(config.personaMix()).containsExactly(Map.entry(Persona.STUDENT, 25), Map.entry(Persona.RETIREE, 75));
        assertThat(config.rates().cardTesting()).isEqualTo(0.5);
    }

    @Test
    void rejectsARateOutsideZeroToOne() {
        assertThatThrownBy(() -> SimulatorConfig.fromEnvironment(Map.of("SIM_RATE_PRICE_HIKE", "1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SIM_RATE_PRICE_HIKE");
    }

    @Test
    void rejectsAnUnknownPersona() {
        assertThatThrownBy(() -> SimulatorConfig.fromEnvironment(Map.of("SIM_PERSONA_MIX", "ASTRONAUT:1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scenariosStartWhenTheWarmUpEnds() {
        SimulatorConfig config = SimulatorConfig.fromEnvironment(Map.of(
                "SIM_START_DATE", "2026-01-01", "SIM_ISSUES_START_AFTER_DAYS", "30"));

        assertThat(config.scenariosActiveOn(LocalDate.of(2026, 1, 30))).isFalse();
        assertThat(config.scenariosActiveOn(LocalDate.of(2026, 1, 31))).isTrue();
    }
}
