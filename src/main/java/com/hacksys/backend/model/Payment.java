package com.hacksys.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Payment {

    public enum Status {
        PENDING, SUCCESS, FAILED, REFUNDED, DUPLICATE
    }

    private String id;
    private String orderId;
    private String userId;
    private double amount;
    private Status status;
    private Instant createdAt;
    private Instant processedAt;
    private String failureReason;
    // Intentional: no idempotency key field — enables duplicate charges on retry
    private int attemptCount;

    public Payment() {}

    public Payment(String id, String orderId, String userId, double amount) {
        this.id = id;
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.attemptCount = 1;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) {
        this.status = status;
        this.processedAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String reason) { this.failureReason = reason; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int count) { this.attemptCount = count; }
}
