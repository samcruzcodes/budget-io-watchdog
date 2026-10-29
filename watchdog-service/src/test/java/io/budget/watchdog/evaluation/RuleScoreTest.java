package io.budget.watchdog.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.budget.watchdog.schema.IssueType;
import org.junit.jupiter.api.Test;

class RuleScoreTest {

    private static RuleScore score(long tp, long fp, long unlabeled, long scenarios, long caught) {
        return RuleScore.of("duplicate-charge", IssueType.DUPLICATE_CHARGE, tp, fp, unlabeled, scenarios, caught);
    }

    @Test
    void precisionCountsAlertsAndRecallCountsScenarios() {
        // Phase 2's run 1 for duplicate-charge: 65 of 133 alerts true, all 65 duplicated pairs caught.
        RuleScore score = score(65, 68, 0, 65, 65);

        assertThat(score.alerts()).isEqualTo(133);
        assertThat(score.precision()).isCloseTo(0.4887, within(0.0001));
        assertThat(score.recall()).isEqualTo(1.0);
        assertThat(score.falseNegatives()).isZero();
    }

    @Test
    void falseNegativesAreTheScenariosNoAlertCaught() {
        RuleScore score = score(26, 2, 0, 43, 18);

        assertThat(score.falseNegatives()).isEqualTo(25);
        assertThat(score.recall()).isCloseTo(18 / 43.0, within(1e-9));
    }

    @Test
    void unlabeledAlertsCountAsAlertsButNotInPrecision() {
        RuleScore score = score(3, 1, 6, 4, 3);

        assertThat(score.alerts()).isEqualTo(10);
        assertThat(score.unlabeledAlerts()).isEqualTo(6);
        assertThat(score.precision()).isEqualTo(0.75);
    }

    @Test
    void nothingToDivideGivesNullNotZero() {
        RuleScore empty = score(0, 0, 0, 0, 0);

        assertThat(empty.precision()).isNull();
        assertThat(empty.recall()).isNull();
        assertThat(empty.falseNegatives()).isZero();
    }

    @Test
    void onlyUnlabeledAlertsStillHaveNoPrecision() {
        assertThat(score(0, 0, 5, 0, 0).precision()).isNull();
    }

    @Test
    void scenariosButNoAlertsIsZeroRecallNotNull() {
        RuleScore missedEverything = score(0, 0, 0, 7, 0);

        assertThat(missedEverything.recall()).isEqualTo(0.0);
        assertThat(missedEverything.precision()).isNull();
        assertThat(missedEverything.falseNegatives()).isEqualTo(7);
    }

    @Test
    void onlyFalseAlarmsIsZeroPrecision() {
        assertThat(score(0, 4, 0, 2, 0).precision()).isEqualTo(0.0);
    }
}
