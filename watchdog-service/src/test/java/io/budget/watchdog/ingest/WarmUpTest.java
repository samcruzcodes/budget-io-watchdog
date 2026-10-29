package io.budget.watchdog.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.budget.watchdog.rules.RuleEngine;
import io.budget.watchdog.schema.Category;
import io.budget.watchdog.schema.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.PlatformTransactionManager;

/** The order of a startup: replay first, then start the listener. Postgres and Kafka are stand-ins here. */
class WarmUpTest {

    private static final Instant NEWEST = Instant.parse("2031-06-15T12:00:00Z");

    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final RuleEngine ruleEngine = mock(RuleEngine.class);
    private final KafkaListenerEndpointRegistry listeners = mock(KafkaListenerEndpointRegistry.class);
    private final MessageListenerContainer listener = mock(MessageListenerContainer.class);

    private final WarmUp warmUp = new WarmUp(new WarmUp.Settings(Duration.ofDays(90)), transactions, ruleEngine,
            listeners, mock(PlatformTransactionManager.class));

    WarmUpTest() {
        when(listeners.getListenerContainer(TransactionListener.ID)).thenReturn(listener);
    }

    @Test
    void replaysTheLast90DaysBeforeTheNewestTransactionThenStartsTheListener() {
        Transaction older = charge("card-0001");
        Transaction newer = charge("card-0002");
        when(transactions.newestOccurredAt()).thenReturn(Optional.of(NEWEST));
        doAnswer(call -> {
            Consumer<Transaction> action = call.getArgument(1);
            List.of(older, newer).forEach(action);
            return null;
        }).when(transactions).forEachSince(eq(NEWEST.minus(Duration.ofDays(90))), any());

        warmUp.run(null);

        InOrder order = inOrder(ruleEngine, listener);
        order.verify(ruleEngine).evaluate(older);
        order.verify(ruleEngine).evaluate(newer);
        order.verify(listener).start();
    }

    @Test
    void withNothingStoredItStartsTheListenerRightAway() {
        when(transactions.newestOccurredAt()).thenReturn(Optional.empty());

        warmUp.run(null);

        verify(transactions, never()).forEachSince(any(), any());
        verify(listener).start();
    }

    @Test
    void aLookbackOfZeroIsRejectedAtStartup() {
        assertThatThrownBy(() -> new WarmUp.Settings(Duration.ZERO))
                .hasMessageContaining("watchdog.warm-up.lookback must be positive");
        assertThat(new WarmUp.Settings(Duration.ofDays(90)).lookback()).isEqualTo(Duration.ofDays(90));
    }

    private static Transaction charge(String cardId) {
        return new Transaction(Transaction.SCHEMA_VERSION, UUID.randomUUID(), "SIMULATOR", "user-0001", cardId,
                "Netflix", Category.SUBSCRIPTIONS, 1_549, "USD", "US", NEWEST, NEWEST);
    }
}
