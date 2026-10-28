package io.budget.watchdog.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;

class GroundTruthLabelTest {

    private static final Schema SCHEMA = ContractSchemas.load("/schemas/ground-truth-label.v1.schema.json");
    private static final UUID TRANSACTION = UUID.fromString("0f9d8a52-3c1e-3b7a-9e64-2d5f1c8b7a10");
    private static final UUID SCENARIO = UUID.fromString("8e1f7c44-0d2b-3a59-b6c1-7f3e9a2d4b85");

    static Stream<GroundTruthLabel> validLabels() {
        return Stream.of(
                new GroundTruthLabel(1, TRANSACTION, "STUDENT", IssueType.NONE, null, null),
                new GroundTruthLabel(1, TRANSACTION, "FAMILY", IssueType.CARD_TESTING, SCENARIO, null),
                new GroundTruthLabel(1, TRANSACTION, "FREQUENT_TRAVELER", IssueType.NONE, SCENARIO, EdgeCase.LEGIT_TRAVEL));
    }

    @ParameterizedTest
    @MethodSource("validLabels")
    void validLabelSurvivesAJsonRoundTripAndMatchesTheSchema(GroundTruthLabel label) {
        String json = MessageJson.write(label);

        assertThat(MessageJson.read(json, GroundTruthLabel.class)).isEqualTo(label);
        assertThat(SCHEMA.validate(json, InputFormat.JSON)).isEmpty();
    }

    @Test
    void injectedIssueNeedsAScenario() {
        assertThatThrownBy(() -> new GroundTruthLabel(1, TRANSACTION, "STUDENT", IssueType.DUPLICATE_CHARGE, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenarioId");
    }

    @Test
    void edgeCaseNeedsAScenario() {
        assertThatThrownBy(() -> new GroundTruthLabel(1, TRANSACTION, "STUDENT", IssueType.NONE, null, EdgeCase.DOUBLE_COFFEE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenarioId");
    }

    @Test
    void edgeCaseIsLegitimateSoItCannotAlsoBeAnIssue() {
        assertThatThrownBy(() -> new GroundTruthLabel(
                1, TRANSACTION, "STUDENT", IssueType.DUPLICATE_CHARGE, SCENARIO, EdgeCase.DOUBLE_COFFEE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONE");
    }

    @Test
    void missingFieldIsRejectedEvenWhenItCouldBeNull() {
        String json = """
                {"schemaVersion": 1, "transactionId": "0f9d8a52-3c1e-3b7a-9e64-2d5f1c8b7a10", "persona": "STUDENT",
                 "issueType": "NONE", "scenarioId": null}
                """;

        assertThatThrownBy(() -> MessageJson.read(json, GroundTruthLabel.class)).isInstanceOf(JacksonException.class);
        assertThat(SCHEMA.validate(json, InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void unknownIssueTypeIsRejectedByTheRecordAndTheSchema() {
        String json = """
                {"schemaVersion": 1, "transactionId": "0f9d8a52-3c1e-3b7a-9e64-2d5f1c8b7a10", "persona": "STUDENT",
                 "issueType": "SHOPLIFTING", "scenarioId": null, "edgeCase": null}
                """;

        assertThatThrownBy(() -> MessageJson.read(json, GroundTruthLabel.class)).isInstanceOf(JacksonException.class);
        assertThat(SCHEMA.validate(json, InputFormat.JSON)).isNotEmpty();
    }
}
