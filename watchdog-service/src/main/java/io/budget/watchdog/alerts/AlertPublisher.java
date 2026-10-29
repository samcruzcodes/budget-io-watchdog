package io.budget.watchdog.alerts;

import io.budget.watchdog.schema.Alert;
import io.budget.watchdog.schema.MessageJson;
import io.budget.watchdog.schema.Topics;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes alerts to the {@code alerts} topic, keyed by card, so each card's alerts stay in order. */
@Component
public class AlertPublisher {

    private final KafkaTemplate<String, String> kafka;

    public AlertPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    /**
     * Sends the alert and waits until Kafka has stored it. Throwing on failure matters: the listener's retry then
     * republishes it, because the alert is still marked unpublished in Postgres.
     */
    public void publish(Alert alert) {
        try {
            kafka.send(Topics.ALERTS, alert.cardId(), MessageJson.write(alert)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing alert " + alert.alertId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not publish alert " + alert.alertId(), e);
        }
    }
}
