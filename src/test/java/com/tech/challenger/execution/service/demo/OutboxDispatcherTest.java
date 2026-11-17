package com.tech.challenger.execution.service.demo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxDispatcherTest {
    @Test
    void publishesWithConfirmationAndUpdatesOrder() {
        OutboxRepository repository = mock(OutboxRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        RemoteClients remote = mock(RemoteClients.class);
        Budget budget = new Budget(3L, "Reparo", BigDecimal.TEN);
        OutboxEvent event = new OutboxEvent(budget, "PAYMENT_APPROVED");
        when(repository.findReady(any())).thenReturn(List.of(event));
        when(repository.findLockedById(event.getId())).thenReturn(Optional.of(event));
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        OutboxDispatcher dispatcher = new OutboxDispatcher(repository, transactions, rabbit, remote, "billing.exchange");
        dispatcher.dispatch();

        assertThat(event.isPublished()).isTrue();
        assertThat(event.isOsUpdated()).isTrue();
        verify(remote).deliverOrder(3L);
    }

    @Test
    void failedPublicationRemainsPendingForRetry() {
        OutboxRepository repository = mock(OutboxRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        RemoteClients remote = mock(RemoteClients.class);
        OutboxEvent event = new OutboxEvent(new Budget(4L, "Reparo", BigDecimal.TEN), "BUDGET_CREATED");
        when(repository.findReady(any())).thenReturn(List.of(event));
        when(repository.findLockedById(event.getId())).thenReturn(Optional.of(event));
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        doThrow(new IllegalStateException("broker indisponivel")).when(rabbit)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        new OutboxDispatcher(repository, transactions, rabbit, remote, "billing.exchange").dispatch();

        assertThat(event.isPublished()).isFalse();
        assertThat(event.isOsUpdated()).isTrue();
        verify(remote, never()).deliverOrder(anyLong());
    }
}
