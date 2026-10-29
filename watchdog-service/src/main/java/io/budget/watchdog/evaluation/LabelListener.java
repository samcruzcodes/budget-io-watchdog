package io.budget.watchdog.evaluation;

import io.budget.watchdog.ingest.InvalidMessageException;
import io.budget.watchdog.schema.GroundTruthLabel;
import io.budget.watchdog.schema.MessageJson;
import io.budget.watchdog.schema.Topics;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Stores the simulator's answer key from {@code transaction-labels}, in its own consumer group, so labels never pass
 * through the detection code. It starts right away: labels don't feed the rules, so it doesn't wait for the warm-up.
 * A label Kafka delivers twice is stored once.
 */
@Component
class LabelListener {

    private final LabelRepository labels;
    private final Clock clock;

    LabelListener(LabelRepository labels, Clock clock) {
        this.labels = labels;
        this.clock = clock;
    }

    @KafkaListener(topics = Topics.TRANSACTION_LABELS, groupId = "watchdog-labels")
    void onLabel(ConsumerRecord<String, String> record) {
        labels.insertIfAbsent(parse(record.value()), clock.instant());
    }

    /** @throws InvalidMessageException if it isn't a valid label v1, which sends it to transaction-labels.DLT */
    static GroundTruthLabel parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidMessageException("empty message", null);
        }
        try {
            return MessageJson.read(json, GroundTruthLabel.class);
        } catch (JacksonException e) {
            throw new InvalidMessageException("not a valid GroundTruthLabel v1: " + e.getOriginalMessage(), e);
        }
    }
}
