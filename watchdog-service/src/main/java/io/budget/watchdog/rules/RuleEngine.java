package io.budget.watchdog.rules;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.Transaction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Runs every detection rule on a new transaction. Spring passes in every {@link DetectionRule} bean it finds. */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<DetectionRule> rules;

    // One lock per card. A card normally only arrives on one consumer thread, so nothing ever waits on it. But Kafka
    // can move a partition to another thread (it does at startup), and Java only guarantees the new thread sees the
    // old thread's changes to the card's state if they hand over through a lock. It also covers Phase 3's warm-up.
    private final Map<String, Object> cardLocks = new ConcurrentHashMap<>();

    public RuleEngine(List<DetectionRule> rules) {
        // Sorted, so hits always come out in the same order.
        this.rules = rules.stream().sorted(Comparator.comparing(DetectionRule::id)).toList();
        log.info("Detection rules: {}", this.rules.stream().map(DetectionRule::id).toList());
    }

    /** The hits for one new transaction, in rule ID order. Income is money coming in, so no rule looks at it. */
    public List<RuleHit> evaluate(Transaction tx) {
        if (tx.category() == Category.INCOME) {
            return List.of();
        }
        List<RuleHit> hits = new ArrayList<>();
        synchronized (cardLocks.computeIfAbsent(tx.cardId(), card -> new Object())) {
            for (DetectionRule rule : rules) {
                try {
                    rule.evaluate(tx).ifPresent(hits::add);
                } catch (RuntimeException e) {
                    // A bug in one rule shouldn't hide the other rules' hits. Rethrowing wouldn't help either:
                    // Kafka's retry would find the transaction already stored and skip every rule.
                    log.error("Rule {} failed on transaction {}", rule.id(), tx.transactionId(), e);
                }
            }
        }
        return hits;
    }
}
