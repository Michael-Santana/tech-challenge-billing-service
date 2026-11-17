package com.tech.challenger.execution.service.demo;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "budget")
public class Budget {
    public enum Status { PENDING_APPROVAL, APPROVED, REJECTED, PAID }

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;
    @Column(nullable = false)
    private String description;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "preference_id")
    private String preferenceId;
    @Column(name = "checkout_url", length = 2048)
    private String checkoutUrl;
    @Column(name = "payment_id", unique = true)
    private String paymentId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Version
    private long version;

    protected Budget() {}

    Budget(Long orderId, String description, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.description = description;
        this.amount = amount;
        this.status = Status.PENDING_APPROVAL;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
    public String getPreferenceId() { return preferenceId; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public String getPaymentId() { return paymentId; }
    public Instant getCreatedAt() { return createdAt; }
    void setStatus(Status status) { this.status = status; }
    void setPreference(String id, String url) { preferenceId = id; checkoutUrl = url; }
    void setPaymentId(String paymentId) { this.paymentId = paymentId; }
}
