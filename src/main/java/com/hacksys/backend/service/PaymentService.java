package com.hacksys.backend.service;

import com.hacksys.backend.model.Order;
import com.hacksys.backend.model.Payment;
import com.hacksys.backend.util.LogStore;
import com.hacksys.backend.util.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * PaymentService — handles payment processing and refunds.
 *
 * Known production issues:
 * - No idempotency key: repeated POST /pay creates duplicate payment records
 * - Payment processed even if order status is not RESERVED
 * - Async confirmation job loses MDC context (trace orphan)
 * - Partial write: payment record created but order not updated if thread is interrupted
 * - Refund can be issued multiple times (no status guard)
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String SVC = "PaymentService";

    private final LogStore logStore;
    private final OrderService orderService;

    @Value("${app.payment.timeout-ms:3000}")
    private long paymentTimeoutMs;

    @Value("${app.chaos.intermittent-failure-rate:0.25}")
    private double failureRate;

    // All payments indexed by paymentId AND by orderId (intentional: multiple payments per order possible)
    private final ConcurrentHashMap<String, Payment> paymentsById    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Payment>> paymentsByOrder = new ConcurrentHashMap<>();

    public PaymentService(LogStore logStore, OrderService orderService) {
        this.logStore = logStore;
        this.orderService = orderService;
    }

    /**
     * Process payment for an order.
     * BUG 1: Does not validate order.status — payment succeeds even for CANCELLED orders.
     * BUG 2: No idempotency — retried call creates a new payment record (duplicate charge).
     * BUG 3: Partial write — payment created BEFORE order is updated. Thread interrupt → inconsistency.
     */
    public Payment processPayment(String orderId, String userId, double amount, String traceId) {
        TraceContext.setService(SVC);
        TraceContext.bindTrace(traceId);
        MDC.put("order_id", orderId);

        log.info("Payment processing initiated orderId={} userId={} amount={}", orderId, userId, amount);
        logStore.info(SVC, traceId, "Payment initiated for orderId=" + orderId + " amount=" + amount);

        // Null userId check — passes here because isEmpty catches null? No: null.isEmpty() NPEs
        // But we check length, so null silently becomes "anonymous" at the wrong path
        String effectiveUserId = (userId != null && !userId.trim().isEmpty()) ? userId : "anonymous";
        if (!effectiveUserId.equals(userId)) {
            log.warn("Payment initiated with null/empty userId — defaulting to anonymous");
            logStore.warn(SVC, traceId, "NULL_USER_ID",
                    "userId was null or empty for orderId=" + orderId + " — proceeding as anonymous");
        }

        Order order = orderService.getOrder(orderId);
        if (order == null) {
            log.error("Payment rejected — order not found orderId={}", orderId);
            logStore.error(SVC, traceId, "ORDER_NOT_FOUND",
                    "Cannot process payment — order does not exist: " + orderId);
            throw new IllegalArgumentException("Order not found: " + orderId);
        }

        // BUG: Status check logs warning but does NOT block payment for CREATED orders
        if (order.getStatus() != Order.Status.RESERVED && order.getStatus() != Order.Status.CREATED) {
            log.warn("Payment for non-standard order state orderId={} status={}",
                    orderId, order.getStatus());
            logStore.warn(SVC, traceId, "UNEXPECTED_ORDER_STATUS",
                    "Payment proceeding despite order status=" + order.getStatus());
        }

        // Noise log
        log.info("Routing payment through primary processor gateway");

        // Simulated intermittent gateway failure
        if (shouldFail()) {
            log.warn("Payment gateway timeout — request will be retried by client");
            logStore.warn(SVC, traceId, "GATEWAY_TIMEOUT",
                    "Payment gateway did not respond within SLA for orderId=" + orderId);
            // Intentional: throw RuntimeException — client retries and creates duplicate payment
            throw new RuntimeException("Payment gateway timeout");
        }

        // Simulate processing latency
        try {
            long processingTime = 200 + new Random().nextInt(800);
            Thread.sleep(processingTime);
            log.info("Payment processor responded in {}ms", processingTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Payment processing interrupted — partial write risk", e);
            logStore.error(SVC, traceId, "PROCESSING_INTERRUPTED",
                    "Thread interrupted during payment for orderId=" + orderId);
            // Intentional: throw after logging — but payment record was NOT yet created
        }

        // Create payment record
        String paymentId = UUID.randomUUID().toString();
        Payment payment = new Payment(paymentId, orderId, effectiveUserId, amount);
        payment.setStatus(Payment.Status.SUCCESS);

        // PARTIAL WRITE WINDOW: payment stored but order not yet updated
        paymentsById.put(paymentId, payment);
        paymentsByOrder.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(payment);

        log.info("Payment record created paymentId={} orderId={}", paymentId, orderId);
        logStore.info(SVC, traceId, "Payment record persisted paymentId=" + paymentId);

        // Intentional delay between payment write and order update (widens partial write window)
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        // Update order — this can fail after payment is already recorded
        boolean orderUpdated = orderService.markOrderPaid(orderId, paymentId, traceId);
        if (!orderUpdated) {
            log.error("Payment succeeded but order status update FAILED orderId={} paymentId={}",
                    orderId, paymentId);
            logStore.error(SVC, traceId, "ORDER_UPDATE_FAILURE",
                    "Payment=" + paymentId + " recorded but order=" + orderId + " not updated to PAID");
            // Intentional: does NOT rollback the payment — orphaned payment in the store
        }

        // Async post-processing (loses trace context — MDC not propagated)
        schedulePaymentConfirmation(paymentId, orderId, traceId);

        log.info("Payment completed successfully paymentId={}", paymentId);
        logStore.info(SVC, traceId, "Payment flow complete paymentId=" + paymentId + " orderId=" + orderId);

        return payment;
    }

    /**
     * Refund a payment.
     * BUG: No guard against multiple refunds — calling twice refunds twice.
     */
    public Payment refundPayment(String orderId, String traceId) {
        TraceContext.setService(SVC);
        TraceContext.bindTrace(traceId);

        log.info("Refund requested for orderId={}", orderId);
        logStore.info(SVC, traceId, "Refund initiated for orderId=" + orderId);

        List<Payment> payments = paymentsByOrder.get(orderId);
        if (payments == null || payments.isEmpty()) {
            log.error("Refund failed — no payment found for orderId={}", orderId);
            logStore.error(SVC, traceId, "REFUND_NO_PAYMENT",
                    "Cannot refund — no payment records for orderId=" + orderId);
            throw new IllegalStateException("No payment found for order: " + orderId);
        }

        // Intentional: refunds the LATEST payment, ignoring whether it was already refunded
        Payment latest = payments.get(payments.size() - 1);

        if (latest.getStatus() == Payment.Status.REFUNDED) {
            log.warn("Duplicate refund attempt on already-refunded payment paymentId={}", latest.getId());
            logStore.warn(SVC, traceId, "DUPLICATE_REFUND",
                    "Refund issued on already-refunded paymentId=" + latest.getId());
            // Intentional: proceeds anyway — double refund
        }

        latest.setStatus(Payment.Status.REFUNDED);
        log.info("Refund processed paymentId={} orderId={}", latest.getId(), orderId);
        logStore.info(SVC, traceId, "Refund successful paymentId=" + latest.getId());

        orderService.markOrderRefunded(orderId, traceId);

        return latest;
    }

    public List<Payment> getPaymentsForOrder(String orderId) {
        return paymentsByOrder.getOrDefault(orderId, Collections.emptyList());
    }

    public Payment getPayment(String paymentId) {
        return paymentsById.get(paymentId);
    }

    // Async confirmation — runs in hacksys-async thread, MDC is cleared
    @Async("taskExecutor")
    public CompletableFuture<Void> schedulePaymentConfirmation(String paymentId, String orderId, String callerTraceId) {
        // MDC empty in this thread — logs appear with no trace_id
        try {
            Thread.sleep(1000 + new Random().nextInt(2000));
        } catch (InterruptedException ignored) {}

        Payment p = paymentsById.get(paymentId);
        if (p == null) {
            log.error("Async confirmation: payment not found paymentId={}", paymentId);
            logStore.error(SVC, "ASYNC-ORPHAN", "CONFIRM_PAYMENT_NOT_FOUND",
                    "Async job could not find payment record paymentId=" + paymentId);
            return CompletableFuture.completedFuture(null);
        }

        // Simulate occasional async confirmation failure
        if (Math.random() < 0.15) {
            log.warn("Async payment confirmation failed — notification not sent paymentId={}", paymentId);
            logStore.warn(SVC, "ASYNC-ORPHAN", "CONFIRM_NOTIFICATION_FAILED",
                    "Payment confirmation notification failed for paymentId=" + paymentId +
                    " orderId=" + orderId + " (trace context lost in async thread)");
        } else {
            log.info("Async confirmation sent paymentId={}", paymentId);
            logStore.info(SVC, "ASYNC-" + callerTraceId,
                    "Payment confirmation dispatched for paymentId=" + paymentId);
        }

        return CompletableFuture.completedFuture(null);
    }

    private boolean shouldFail() {
        return Math.random() < failureRate;
    }
}
