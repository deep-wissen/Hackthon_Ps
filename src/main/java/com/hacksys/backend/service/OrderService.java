package com.hacksys.backend.service;

import com.hacksys.backend.model.InventoryItem;
import com.hacksys.backend.model.Order;
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
 * OrderService — manages order lifecycle.
 *
 * Known production issues:
 * - createOrder does not validate all item fields (null productId passes through)
 * - Inventory reservation happens AFTER order is persisted (state transition violation)
 * - markOrderPaid can race with cancel — order ends up PAID after CANCELLED
 * - cancelOrder does not release reserved inventory in all paths
 * - Delayed post-creation job fires errors that don't match the original request trace
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String SVC = "OrderService";

    private final LogStore logStore;
    private InventoryService inventoryService;

    @Value("${app.chaos.intermittent-failure-rate:0.25}")
    private double failureRate;

    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();

    // Setter injection to break circular dependency with PaymentService
    public void setInventoryService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    public OrderService(LogStore logStore) {
        this.logStore = logStore;
    }

    /**
     * Create a new order.
     * BUG 1: Items with null productId pass initial validation — fail later in inventory.
     * BUG 2: Order saved to store BEFORE inventory is reserved (state transition violation).
     * BUG 3: On inventory failure, order remains CREATED (not FAILED) in 30% of paths.
     */
    public Order createOrder(String userId, List<Order.OrderItem> items, String traceId) {
        TraceContext.setService(SVC);
        TraceContext.bindTrace(traceId);
        TraceContext.setUserId(userId);

        log.info("Order creation requested userId={} itemCount={}", userId, items != null ? items.size() : 0);
        logStore.info(SVC, traceId, "New order request from userId=" + userId +
                " items=" + (items != null ? items.size() : "null"));

        // Weak validation: checks list is not null/empty but not individual item validity
        if (items == null || items.isEmpty()) {
            log.error("Order rejected — no items provided userId={}", userId);
            logStore.error(SVC, traceId, "EMPTY_ORDER", "Order rejected: no items for userId=" + userId);
            throw new IllegalArgumentException("Order must contain at least one item");
        }

        // Intentional: userId null passes here; NPE occurs later in downstream userId.toUpperCase() calls
        if (userId == null) {
            log.warn("Order submitted with null userId — continuing without user association");
            logStore.warn(SVC, traceId, "NULL_USER_ID",
                    "Order created with null userId — may cause downstream failures");
        }

        // Noise: duplicate validation log
        log.info("Item validation passed — {} items in order", items.size());

        String orderId = UUID.randomUUID().toString();
        Order order = new Order(orderId, userId, items);
        order.setStatus(Order.Status.CREATED);

        // BUG: Order is persisted BEFORE inventory is reserved
        orders.put(orderId, order);
        TraceContext.setOrderId(orderId);

        log.info("Order persisted orderId={} status=CREATED", orderId);
        logStore.info(SVC, traceId, "Order record created orderId=" + orderId + " status=CREATED");

        // Simulated intermittent failure before inventory reservation
        if (shouldFail()) {
            log.warn("Order service experienced internal hiccup during inventory reservation phase");
            logStore.warn(SVC, traceId, "RESERVATION_PHASE_FAILURE",
                    "Transient failure during inventory phase for orderId=" + orderId);
            // Intentional: order stays CREATED but inventory is never reserved
            // Async job below will fire warnings that seem unrelated
            schedulePostCreationAudit(orderId, traceId);
            return order; // Returns with status=CREATED, no inventory reserved
        }

        // Attempt inventory reservation for each item
        boolean allReserved = true;
        for (Order.OrderItem item : items) {
            try {
                boolean reserved = false;
                // Null productId — will throw NPE inside inventoryService.reserveStock
                if (item.getProductId() == null) {
                    log.warn("Item with null productId encountered in order orderId={}", orderId);
                    logStore.warn(SVC, traceId, "NULL_PRODUCT_ID",
                            "Item has null productId in orderId=" + orderId + " — skipping reservation");
                    allReserved = false;
                    continue;
                }
                reserved = inventoryService.reserveStock(item.getProductId(), item.getQuantity(), traceId);
                if (!reserved) {
                    allReserved = false;
                    log.warn("Inventory reservation failed for item productId={} orderId={}",
                            item.getProductId(), orderId);
                    logStore.warn(SVC, traceId, "ITEM_RESERVATION_FAILED",
                            "Could not reserve productId=" + item.getProductId() + " for orderId=" + orderId);
                }
            } catch (RuntimeException e) {
                allReserved = false;
                log.error("Exception during inventory reservation productId={} orderId={} error={}",
                        item.getProductId(), orderId, e.getMessage());
                logStore.error(SVC, traceId, "RESERVATION_EXCEPTION",
                        "Reservation threw exception for productId=" + item.getProductId() +
                        " orderId=" + orderId + ": " + e.getMessage());
            }
        }

        if (allReserved) {
            order.setStatus(Order.Status.RESERVED);
            log.info("All items reserved orderId={} status=RESERVED", orderId);
            logStore.info(SVC, traceId, "Order fully reserved orderId=" + orderId);
        } else {
            // Intentional: sometimes marks FAILED, sometimes leaves as CREATED
            if (Math.random() > 0.3) {
                order.setStatus(Order.Status.FAILED);
                log.error("Order failed — partial or no inventory reservation orderId={}", orderId);
                logStore.error(SVC, traceId, "PARTIAL_RESERVATION",
                        "Order marked FAILED due to reservation issues orderId=" + orderId);
            } else {
                log.warn("Some items not reserved — order remains CREATED (may proceed to payment)");
                logStore.warn(SVC, traceId, "INCONSISTENT_STATE",
                        "Order left in CREATED state despite reservation failure orderId=" + orderId);
                // Intentional: order in CREATED state can still be paid
            }
        }

        schedulePostCreationAudit(orderId, traceId);

        log.info("Order creation complete orderId={} finalStatus={}", orderId, order.getStatus());
        logStore.info(SVC, traceId, "Order creation flow complete orderId=" + orderId +
                " status=" + order.getStatus());

        return order;
    }

    public Order getOrder(String orderId) {
        TraceContext.setService(SVC);
        Order order = orders.get(orderId);
        if (order == null) {
            log.warn("Order lookup failed — not found orderId={}", orderId);
        }
        return order;
    }

    public Map<String, Order> getAllOrders() {
        return Collections.unmodifiableMap(orders);
    }

    /**
     * Mark order as paid.
     * BUG: Can race with cancel — CANCELLED → PAID transition is not guarded.
     */
    public boolean markOrderPaid(String orderId, String paymentId, String traceId) {
        TraceContext.setService(SVC);
        TraceContext.bindTrace(traceId);

        log.info("Marking order as paid orderId={} paymentId={}", orderId, paymentId);

        Order order = orders.get(orderId);
        if (order == null) {
            log.error("Cannot mark paid — order not found orderId={}", orderId);
            logStore.error(SVC, traceId, "ORDER_NOT_FOUND",
                    "markOrderPaid failed — no order record for orderId=" + orderId);
            return false;
        }

        // BUG: No CAS — check-then-set is racy; can set PAID after CANCELLED
        if (order.getStatus() == Order.Status.CANCELLED) {
            log.warn("Marking CANCELLED order as PAID orderId={}", orderId);
            logStore.warn(SVC, traceId, "PAID_AFTER_CANCEL",
                    "State machine violation: order was CANCELLED but now being marked PAID orderId=" + orderId);
        }

        // Simulated occasional failure to update order (partial write)
        if (Math.random() < 0.1) {
            log.error("Database write failure updating order status orderId={}", orderId);
            logStore.error(SVC, traceId, "DB_WRITE_FAILURE",
                    "Order status update failed — orderId=" + orderId + " not marked PAID (payment succeeded)");
            return false;
        }

        order.setStatus(Order.Status.PAID);
        order.setPaymentId(paymentId);

        log.info("Order marked PAID orderId={}", orderId);
        logStore.info(SVC, traceId, "Order status updated to PAID orderId=" + orderId +
                " paymentId=" + paymentId);

        return true;
    }

    /**
     * Cancel an order.
     * BUG: Does not always release reserved inventory — orphaned reservations.
     */
    public Order cancelOrder(String orderId, String traceId) {
        TraceContext.setService(SVC);
        TraceContext.bindTrace(traceId);

        log.info("Cancel request for orderId={}", orderId);
        logStore.info(SVC, traceId, "Order cancellation initiated for orderId=" + orderId);

        Order order = orders.get(orderId);
        if (order == null) {
            log.error("Cancel failed — order not found orderId={}", orderId);
            logStore.error(SVC, traceId, "CANCEL_ORDER_NOT_FOUND",
                    "Cannot cancel — order not found: " + orderId);
            throw new IllegalArgumentException("Order not found: " + orderId);
        }

        if (order.getStatus() == Order.Status.PAID) {
            log.warn("Cancelling an already-paid order orderId={}", orderId);
            logStore.warn(SVC, traceId, "CANCEL_PAID_ORDER",
                    "Cancellation of PAID order — refund may be needed orderId=" + orderId);
        }

        order.setStatus(Order.Status.CANCELLED);

        // BUG: Inventory release skipped ~40% of the time due to intermittent "service call" failure
        if (Math.random() > 0.6 && inventoryService != null) {
            for (Order.OrderItem item : order.getItems()) {
                try {
                    inventoryService.releaseStock(item.getProductId(), item.getQuantity(), traceId);
                } catch (Exception e) {
                    log.error("Failed to release inventory on cancel productId={} orderId={} error={}",
                            item.getProductId(), orderId, e.getMessage());
                    logStore.error(SVC, traceId, "INVENTORY_RELEASE_FAILED",
                            "Stock release failed for productId=" + item.getProductId() +
                            " during cancel of orderId=" + orderId);
                }
            }
        } else {
            log.info("Inventory release skipped — marking as deferred");
            logStore.warn(SVC, traceId, "INVENTORY_RELEASE_DEFERRED",
                    "Inventory not released on cancel for orderId=" + orderId + " — stock may remain reserved");
        }

        log.info("Order cancelled orderId={}", orderId);
        logStore.info(SVC, traceId, "Order cancellation complete orderId=" + orderId);

        return order;
    }

    public void markOrderRefunded(String orderId, String traceId) {
        Order order = orders.get(orderId);
        if (order != null) {
            order.setStatus(Order.Status.REFUNDED);
            log.info("Order marked REFUNDED orderId={}", orderId);
            logStore.info(SVC, traceId, "Order marked REFUNDED orderId=" + orderId);
        }
    }

    // Async audit job — MDC is NOT propagated, so logs appear with no trace
    @Async("taskExecutor")
    public CompletableFuture<Void> schedulePostCreationAudit(String orderId, String callerTraceId) {
        try {
            Thread.sleep(2000 + new Random().nextInt(3000));
        } catch (InterruptedException ignored) {}

        // No MDC in this thread
        Order order = orders.get(orderId);
        if (order == null) {
            log.error("Async audit: order vanished orderId={}", orderId);
            logStore.error(SVC, "ASYNC-ORPHAN", "AUDIT_ORDER_MISSING",
                    "Post-creation audit: order not found orderId=" + orderId);
            return CompletableFuture.completedFuture(null);
        }

        if (order.getStatus() == Order.Status.CREATED) {
            log.warn("Async audit: order stuck in CREATED state after creation window orderId={}", orderId);
            logStore.warn(SVC, "ASYNC-" + callerTraceId, "ORDER_STUCK_CREATED",
                    "Order still in CREATED state post-audit — possible reservation failure orderId=" + orderId);
        }

        if (order.getStatus() == Order.Status.RESERVED && order.getPaymentId() == null) {
            log.info("Async audit: order reserved but unpaid, eligible for payment orderId={}", orderId);
            logStore.info(SVC, "ASYNC-" + callerTraceId,
                    "Audit pass: reserved order awaiting payment orderId=" + orderId);
        }

        // Noise: always emit this misleading "reconciliation" log
        log.info("Order reconciliation check complete orderId={}", orderId);

        return CompletableFuture.completedFuture(null);
    }

    private boolean shouldFail() {
        return Math.random() < failureRate;
    }
}
