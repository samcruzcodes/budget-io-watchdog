package io.budget.watchdog;

import static io.budget.watchdog.TestKafka.charge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import io.budget.watchdog.TestKafka.PublishedAlert;
import io.budget.watchdog.alerts.AlertPublisher;
import io.budget.watchdog.alerts.AlertRepository;
import io.budget.watchdog.schema.Alert;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The whole path with real Kafka and Postgres: a message in, the transaction and its alerts saved, the alerts
 * published. The spies let a test make one save or one publish fail, the way a database or Kafka hiccup would.
 * The context is closed afterwards, so its listener leaves the consumer group before another test class starts one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class AlertPipelineIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.register(registry);
    }

    private static final Instant MONDAY = Instant.parse("2026-03-02T18:57:21Z");

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    AlertRepository alerts;

    @MockitoSpyBean
    AlertPublisher publisher;

    private String card;
    private Transaction original;
    private Transaction duplicate;

    @BeforeEach
    void aDuplicatedChargeOnANewCard() {
        card = TestKafka.newCard();
        original = charge(card, "Whole Foods Market", "US", 14_084, MONDAY);
        duplicate = charge(card, "Whole Foods Market", "US", 14_084, MONDAY.plusSeconds(24)); // a new ID
    }

    @Test
    void aDuplicatedChargeIsSavedAsOneAlertAndPublished() {
        TestKafka.send(original, duplicate);

        awaitPublished(duplicate.transactionId());
        List<Map<String, Object>> rows = alertRowsFor(card);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("transaction_id", duplicate.transactionId())
                .containsEntry("detector", "duplicate-charge")
                .containsEntry("explanation", "Whole Foods Market charged $140.84 twice, 24 seconds apart.");

        List<PublishedAlert> published = TestKafka.alertsPublishedFor(card);
        assertThat(published).isNotEmpty().allSatisfy(message -> {
            assertThat(message.key()).isEqualTo(card);
            assertThat(message.alert().alertId()).isEqualTo(Alert.idFor(duplicate.transactionId(), "duplicate-charge"));
        });
    }

    @Test
    void aResentMessageNeverReachesTheRules() {
        Transaction later = charge(card, "Shell", "US", 4_210, MONDAY.plusSeconds(600));

        // The same message twice, then another charge on the card so we know the resend has been handled (one card's
        // messages are processed in order). If the resend reached the rules, duplicate-charge would match it against
        // itself and raise an alert.
        TestKafka.send(original, original, later);

        await().atMost(Duration.ofSeconds(30)).until(() -> isStored(later.transactionId()));
        assertThat(alertRowsFor(card)).isEmpty();
        assertThat(transactionCountFor(card)).isEqualTo(2);
    }

    @Test
    void ifSavingTheAlertFailsTheRetrySavesItOnceAndTheRulesLearnTheChargeOnce() {
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(call -> {
            List<Alert> toSave = call.getArgument(0);
            boolean ours = !toSave.isEmpty() && toSave.getFirst().cardId().equals(card);
            if (ours && failed.compareAndSet(false, true)) {
                throw new DataAccessResourceFailureException("simulated database hiccup");
            }
            return call.callRealMethod();
        }).when(alerts).insertAll(any());

        TestKafka.send(original, duplicate);

        awaitPublished(duplicate.transactionId());
        assertThat(failed).isTrue();
        // The failed attempt rolled back the transaction row too, so the retry stored it as new.
        assertThat(transactionCountFor(card)).isEqualTo(2);
        // "twice", not "again ... after an identical charge": the rule learned the duplicate once, not on each attempt.
        assertThat(alertRowsFor(card)).singleElement()
                .extracting(row -> row.get("explanation"))
                .isEqualTo("Whole Foods Market charged $140.84 twice, 24 seconds apart.");
    }

    @Test
    void ifPublishingFailsTheRedeliveredMessagePublishesTheSavedAlert() {
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(call -> {
            Alert alert = call.getArgument(0);
            if (alert.cardId().equals(card) && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("simulated Kafka hiccup");
            }
            return call.callRealMethod();
        }).when(publisher).publish(any());

        TestKafka.send(original, duplicate);

        awaitPublished(duplicate.transactionId());
        assertThat(failed).isTrue();
        verify(publisher, atLeast(2)).publish(any());
        assertThat(alertRowsFor(card)).hasSize(1);
        // Whatever reached the topic is the same alert, so a reader can drop duplicates by its ID.
        assertThat(TestKafka.alertsPublishedFor(card)).isNotEmpty()
                .extracting(message -> message.alert().alertId())
                .containsOnly(Alert.idFor(duplicate.transactionId(), "duplicate-charge"));
    }

    private void awaitPublished(UUID transactionId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql(
                        "SELECT count(*) FROM alerts WHERE transaction_id = :id AND published_at IS NOT NULL")
                .param("id", transactionId).query(Long.class).single() == 1);
    }

    private boolean isStored(UUID transactionId) {
        return jdbc.sql("SELECT count(*) FROM transactions WHERE transaction_id = :id")
                .param("id", transactionId).query(Long.class).single() == 1;
    }

    private long transactionCountFor(String cardId) {
        return jdbc.sql("SELECT count(*) FROM transactions WHERE card_id = :card")
                .param("card", cardId).query(Long.class).single();
    }

    private List<Map<String, Object>> alertRowsFor(String cardId) {
        return jdbc.sql("SELECT * FROM alerts WHERE card_id = :card").param("card", cardId).query().listOfRows();
    }
}
