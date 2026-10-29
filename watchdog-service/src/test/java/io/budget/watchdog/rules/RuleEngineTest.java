package io.budget.watchdog.rules;

import static io.budget.watchdog.rules.TestTransactions.charge;
import static org.assertj.core.api.Assertions.assertThat;

import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.IssueType;
import io.budget.watchdog.schema.Transaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    /** A stand-in rule that always or never fires, and remembers what it was shown. */
    private record FakeRule(String id, IssueType detects, boolean fires, List<Transaction> seen)
            implements DetectionRule {

        FakeRule(String id, IssueType detects, boolean fires) {
            this(id, detects, fires, new ArrayList<>());
        }

        @Override
        public Optional<RuleHit> evaluate(Transaction tx) {
            seen.add(tx);
            return fires ? Optional.of(hit("fired")) : Optional.empty();
        }
    }

    /** A rule with a bug. */
    private record BrokenRule(String id, IssueType detects) implements DetectionRule {

        @Override
        public Optional<RuleHit> evaluate(Transaction tx) {
            throw new IllegalStateException("bug in " + id);
        }
    }

    @Test
    void returnsTheHitsOfEveryRuleThatFiresInRuleIdOrder() {
        FakeRule quiet = new FakeRule("b-quiet", IssueType.PRICE_HIKE, false);
        RuleEngine engine = new RuleEngine(List.of(
                new FakeRule("c-fires", IssueType.CARD_TESTING, true),
                quiet,
                new FakeRule("a-fires", IssueType.DUPLICATE_CHARGE, true)));

        List<RuleHit> hits = engine.evaluate(charge().build());

        assertThat(hits).extracting(RuleHit::ruleId).containsExactly("a-fires", "c-fires");
        assertThat(hits.getFirst().issueType()).isEqualTo(IssueType.DUPLICATE_CHARGE);
        assertThat(quiet.seen()).hasSize(1);
    }

    @Test
    void incomeNeverReachesTheRules() {
        FakeRule rule = new FakeRule("a-fires", IssueType.DUPLICATE_CHARGE, true);
        RuleEngine engine = new RuleEngine(List.of(rule));

        Transaction paycheck = charge().merchant("Northwind Payroll").category(Category.INCOME).cents(262_000).build();

        assertThat(engine.evaluate(paycheck)).isEmpty();
        assertThat(rule.seen()).isEmpty();
    }

    @Test
    void aRuleThatThrowsDoesntStopTheOthers() {
        RuleEngine engine = new RuleEngine(List.of(
                new BrokenRule("a-broken", IssueType.PRICE_HIKE),
                new FakeRule("b-fires", IssueType.DUPLICATE_CHARGE, true)));

        assertThat(engine.evaluate(charge().build())).extracting(RuleHit::ruleId).containsExactly("b-fires");
    }

    @Test
    void aCardIsNeverEvaluatedOnTwoThreadsAtOnce() throws InterruptedException {
        CountingRule counter = new CountingRule();
        RuleEngine engine = new RuleEngine(List.of(counter));
        Runnable halfTheWork = () -> {
            for (int i = 0; i < 50_000; i++) {
                engine.evaluate(charge().build()); // a new ID each time, so none of them looks like a retry
            }
        };

        Thread first = new Thread(halfTheWork);
        Thread second = new Thread(halfTheWork);
        first.start();
        second.start();
        first.join();
        second.join();

        // The counter isn't thread-safe, so without the engine's per-card lock the two threads would lose updates.
        assertThat(counter.seen).isEqualTo(100_000);
    }

    @Test
    void aRetryOfTheSameTransactionGetsTheSameHitsWithoutRunningTheRulesAgain() {
        FakeRule rule = new FakeRule("a-fires", IssueType.DUPLICATE_CHARGE, true);
        RuleEngine engine = new RuleEngine(List.of(rule));
        Transaction tx = charge().build();

        List<RuleHit> first = engine.evaluate(tx);
        List<RuleHit> retry = engine.evaluate(tx);

        assertThat(retry).isEqualTo(first).hasSize(1);
        assertThat(rule.seen()).containsExactly(tx);
    }

    @Test
    void theNextTransactionRunsTheRulesAsUsual() {
        FakeRule rule = new FakeRule("a-fires", IssueType.DUPLICATE_CHARGE, true);
        RuleEngine engine = new RuleEngine(List.of(rule));
        Transaction first = charge().build();
        Transaction second = charge().build();

        engine.evaluate(first);
        engine.evaluate(second);
        engine.evaluate(first); // not a retry anymore: the card has moved on

        assertThat(rule.seen()).containsExactly(first, second, first);
    }

    @Test
    void eachCardRemembersOnlyItsOwnLastTransaction() {
        FakeRule rule = new FakeRule("a-fires", IssueType.DUPLICATE_CHARGE, true);
        RuleEngine engine = new RuleEngine(List.of(rule));
        Transaction onCardOne = charge().card("card-0001").build();
        Transaction onCardTwo = charge().card("card-0002").build();

        engine.evaluate(onCardOne);
        engine.evaluate(onCardTwo);
        engine.evaluate(onCardOne); // card one's last transaction is still this one, so it's a retry

        assertThat(rule.seen()).containsExactly(onCardOne, onCardTwo);
    }

    /** Counts what it's shown with a plain int, deliberately not thread-safe. */
    private static final class CountingRule implements DetectionRule {

        int seen;

        @Override
        public String id() {
            return "a-counter";
        }

        @Override
        public IssueType detects() {
            return IssueType.DUPLICATE_CHARGE;
        }

        @Override
        public Optional<RuleHit> evaluate(Transaction tx) {
            seen++;
            return Optional.empty();
        }
    }
}
