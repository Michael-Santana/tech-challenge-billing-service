package com.tech.challenger.execution.service.demo;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
class OutboxEvent {
    @Id private UUID id;
    @Column(name = "budget_id", nullable = false) private UUID budgetId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "order_id", nullable = false) private Long orderId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(nullable = false) private boolean published;
    @Column(name = "os_updated", nullable = false) private boolean osUpdated;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;

    protected OutboxEvent() {}
    OutboxEvent(Budget budget, String eventType) {
        id = UUID.randomUUID();
        budgetId = budget.getId();
        orderId = budget.getOrderId();
        this.eventType = eventType;
        createdAt = Instant.now();
        nextAttemptAt = createdAt;
        osUpdated = !eventType.equals("PAYMENT_APPROVED");
    }
    UUID getId() { return id; }
    UUID getBudgetId() { return budgetId; }
    String getEventType() { return eventType; }
    Long getOrderId() { return orderId; }
    Instant getCreatedAt() { return createdAt; }
    boolean isPublished() { return published; }
    boolean isOsUpdated() { return osUpdated; }
    void markPublished() { published = true; }
    void markOsUpdated() { osUpdated = true; }
    void incrementAttempts() { attempts++; nextAttemptAt = Instant.now().plusSeconds(60); }
}
