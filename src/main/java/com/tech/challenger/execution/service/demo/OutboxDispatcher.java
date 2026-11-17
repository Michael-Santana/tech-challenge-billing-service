package com.tech.challenger.execution.service.demo;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
class MessagingConfiguration {
    @Bean TopicExchange billingExchange(@Value("${app.exchange}") String name) {
        return new TopicExchange(name, true, false);
    }

    @Bean Queue billingEventsQueue() { return new Queue("billing.events.queue", true); }

    @Bean Binding billingEventsBinding(Queue billingEventsQueue, TopicExchange billingExchange) {
        return BindingBuilder.bind(billingEventsQueue).to(billingExchange).with("billing.#");
    }

    @Bean Jackson2JsonMessageConverter billingMessageConverter() { return new Jackson2JsonMessageConverter(); }
}

@Component
class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxRepository outbox;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final RemoteClients remote;
    private final String exchange;

    OutboxDispatcher(OutboxRepository outbox, TransactionTemplate transactions, RabbitTemplate rabbit,
                     RemoteClients remote, @Value("${app.exchange}") String exchange) {
        this.outbox = outbox;
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.remote = remote;
        this.exchange = exchange;
        this.rabbit.setMandatory(true);
    }

    @Scheduled(fixedDelayString = "${app.outbox-delay-ms:10000}")
    void dispatch() {
        for (OutboxEvent event : outbox.findReady(Instant.now())) {
            if (!event.isPublished()) process(event.getId(), true);
            if (!event.isOsUpdated()) process(event.getId(), false);
        }
    }

    private void process(UUID id, boolean publish) {
        try {
            transactions.executeWithoutResult(status -> {
                OutboxEvent event = outbox.findLockedById(id).orElseThrow();
                if (publish && !event.isPublished()) {
                    send(event);
                    event.markPublished();
                } else if (!publish && !event.isOsUpdated()) {
                    remote.deliverOrder(event.getOrderId());
                    event.markOsUpdated();
                }
            });
        } catch (RuntimeException ex) {
            log.error("Falha ao {} evento {}: {}", publish ? "publicar" : "atualizar OS para", id, ex.getMessage(), ex);
            transactions.executeWithoutResult(status -> outbox.findLockedById(id).ifPresent(OutboxEvent::incrementAttempts));
        }
    }

    private void send(OutboxEvent event) {
        CorrelationData correlation = new CorrelationData(event.getId().toString());
        rabbit.convertAndSend(exchange, "billing." + event.getEventType().toLowerCase(),
                Map.of("eventId", event.getId().toString(), "eventType", event.getEventType(),
                        "orderId", event.getOrderId(), "budgetId", event.getBudgetId().toString(),
                        "occurredAt", event.getCreatedAt().toString()), correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ nao confirmou a entrega: " + confirm.getReason());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicacao interrompida", ex);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("Falha aguardando confirmacao RabbitMQ", ex);
        }
    }
}
