package io.budget.watchdog.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

class AlertContractTest {

    private static final Schema SCHEMA = ContractSchemas.load("/schemas/alert.v1.schema.json");
    private static final UUID TRANSACTION = UUID.fromString("c1ff7a9f-99d1-34a8-a5a0-2b0719ce3f42");
    private static final Instant OCCURRED = Instant.parse("2026-03-08T18:57:45Z");
    private static final Instant DETECTED = Instant.parse("2026-10-28T23:40:46.732Z");

    private static Alert alert(IssueType issueType) {
        return new Alert(1, Alert.idFor(TRANSACTION, "duplicate-charge"), TRANSACTION, "user-0002", "card-0002",
                "duplicate-charge", issueType, "Whole Foods Market charged $140.84 twice, 24 seconds apart.",
                OCCURRED, DETECTED);
    }

    @Test
    void validAlertSurvivesAJsonRoundTripAndMatchesTheSchema() {
        String json = MessageJson.write(alert(IssueType.DUPLICATE_CHARGE));

        assertThat(MessageJson.read(json, Alert.class)).isEqualTo(alert(IssueType.DUPLICATE_CHARGE));
        assertThat(SCHEMA.validate(json, InputFormat.JSON)).isEmpty();
    }

    @Test
    void theSameTransactionAndDetectorAlwaysGiveTheSameId() {
        assertThat(Alert.idFor(TRANSACTION, "duplicate-charge")).isEqualTo(Alert.idFor(TRANSACTION, "duplicate-charge"));
        assertThat(Alert.idFor(TRANSACTION, "duplicate-charge")).isNotEqualTo(Alert.idFor(TRANSACTION, "card-testing"));
        // Pinned, so a change to the derivation can't slip through: it would give republished alerts new IDs.
        assertThat(Alert.idFor(TRANSACTION, "duplicate-charge"))
                .isEqualTo(UUID.nameUUIDFromBytes("c1ff7a9f-99d1-34a8-a5a0-2b0719ce3f42/duplicate-charge".getBytes()));
    }

    @Test
    void anAlertCannotBeForIssueTypeNone() {
        assertThatThrownBy(() -> alert(IssueType.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issue type");
    }

    @Test
    void issueTypeNoneIsRejectedByTheRecordAndTheSchema() {
        String json = MessageJson.write(alert(IssueType.DUPLICATE_CHARGE)).replace("DUPLICATE_CHARGE", "NONE");

        assertThatThrownBy(() -> MessageJson.read(json, Alert.class)).isInstanceOf(JacksonException.class);
        assertThat(SCHEMA.validate(json, InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void missingFieldIsRejectedByTheRecordAndTheSchema() {
        String json = MessageJson.write(alert(IssueType.DUPLICATE_CHARGE)).replace(",\"explanation\":", ",\"note\":");

        assertThatThrownBy(() -> MessageJson.read(json, Alert.class)).isInstanceOf(JacksonException.class);
        assertThat(SCHEMA.validate(json, InputFormat.JSON)).isNotEmpty();
    }
}
