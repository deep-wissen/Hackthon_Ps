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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * InventoryService — manages stock levels.
 *
 * Known production issues:
 * - Check-then-act on stock is not atomic; race window exists between read and deduct
 * - Retry on transient failure re-deducts stock (no idempotency key)
 * - Silent exception swallowing on partial failures
 * - reserveStock and deductStock both exist; callers mix them up
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final String SVC = "InventoryService";

    private final LogStore logStore;

    @Value("${app.chaos.intermittent-failure-rate:0.25}")
    private double failureRate;

    // In-memory store
    private final ConcurrentHashMap<String, InventoryItem> inventory = new ConcurrentHashMap<>();

    public InventoryService(LogStore logStore) {
        this.logStore = logStore;
        seedInventory();
    }

    private void seedInventory() {
        inventory.put("PROD-001", new InventoryItem("PROD-001", "Wireless Headphones", 45, 79.99));
        inventory.put("PROD-002", new InventoryItem("PROD-002", "Mechanical Keyboard",  20, 129.99));
        inventory.put("PROD-003", new InventoryItem("PROD-003", "USB-C Hub",            60, 39.99));
        inventory.put("PROD-004", new InventoryItem("PROD-004", "Monitor Stand",        15, 49.99));
        inventory.put("PROD-005", new InventoryItem("PROD-005", "Webcam HD",             8, 89.99));
    }

    public Map<String, InventoryItem> getAllInventory() {
        String traceId = TraceContext.getTraceId();
        TraceContext.setService(SVC);

        log.info("Fetching full inventory snapshot");
        logStore.info(SVC, traceId, "Inventory fetch requested, items=" + inventory.size());

        // Noise: emit spurious cache-check log
        log.info("Cache layer checked — proceeding with primary store");

        return Collections.unmodifiableMap(inventory);
    }

    public InventoryItem getItem(String productId) {
        TraceContext.setService(SVC);
        return inventory.get(productId);
    }

    /**
     * Reserve stock for an order (soft lock).
     * BUG: The check and the decrement are two separate ops — race window allows
     *      two concurrent requests to both pass the stock check and both decrement.
     */
    public boolean reserveStock(String productId, int quantity, String traceId) {
        TraceContext.setService(SVC);
        TraceContext.bindTrace(traceId);
        MDC.put("product_id", productId);

        log.info("Attempting to reserve stock productId={} qty={}", productId, quantity);
        logStore.info(SVC, traceId, "Stock reservation requested for " + productId + " qty=" + quantity);

        InventoryItem item = inventory.get(productId);
        if (item == null) {
            log.warn("Product not found in inventory productId={}", productId);
            logStore.warn(SVC, traceId, "PRODUCT_NOT_FOUND",
                    "Reservation failed — product not found: " + productId);
            return false;
        }

        // Simulated intermittent dependency failure
        if (shouldFail()) {
            log.warn("Inventory data store momentarily unavailable — will retry reservation");
            logStore.warn(SVC, traceId, "STORE_TRANSIENT_FAILURE",
                    "Inventory backend returned timeout on reservation for " + productId);
            // Intentional: throws so caller can retry — but retry has no idempotency
            throw new RuntimeException("Inventory store transient failure");
        }

        // BUG: Non-atomic check-then-act (race condition window)
        int current = item.getStock();
        log.info("Current stock for {} = {}, requesting {}", productId, current, quantity);

        if (current < quantity) {
            log.warn("Insufficient stock productId={} available={} requested={}", productId, current, quantity);
            logStore.warn(SVC, traceId, "INSUFFICIENT_STOCK",
                    "Stock check failed: available=" + current + " requested=" + quantity);
            return false;
        }

        // Intentional: sleep to widen race window
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        // Deduct without verifying stock hasn't changed since the check above
        item.setStock(current - quantity);
        item.setReservedStock(item.getReservedStock() + quantity);
        item.setLastUpdated(Instant.now());

        log.info("Stock reserved productId={} reserved={} remaining={}", productId, quantity, item.getStock());
        logStore.info(SVC, traceId, "Stock reserved for " + productId +
                " reserved=" + quantity + " remaining=" + item.getStock());

        // Noise: duplicate log at different level
        log.debug("Reservation complete — updating last-updated timestamp");

        return true;
    }

    /**
     * Hard deduct (used by payment confirmation path).
     * BUG: Does not check if reserveStock was already called — double-deduction possible.
     */
    public boolean deductStock(String productId, int quantity, String traceId) {
        TraceContext.setService(SVC);
        TraceContext.bindTrace(traceId);

        log.info("Deducting stock productId={} qty={}", productId, quantity);
        logStore.info(SVC, traceId, "Hard stock deduction initiated for " + productId + " qty=" + quantity);

        InventoryItem item = inventory.get(productId);
        if (item == null) {
            // Silent failure: log error but return true — upstream sees success
            log.error("CRITICAL: Deduction attempted on unknown product {}", productId);
            logStore.error(SVC, traceId, "DEDUCT_UNKNOWN_PRODUCT",
                    "Stock deduction silently failed — product does not exist: " + productId);
            // Intentional silent failure: caller receives true, thinks deduction succeeded
            return true;
        }

        int newStock = item.getStockRef().addAndGet(-quantity);
        if (newStock < 0) {
            log.warn("Stock went negative productId={} stock={}", productId, newStock);
            logStore.warn(SVC, traceId, "NEGATIVE_STOCK",
                    "Stock is now negative for " + productId + " value=" + newStock);
            // Intentional: does not rollback to 0 — leaves negative stock in system
        }

        item.setLastUpdated(Instant.now());
        log.info("Stock deducted productId={} newStock={}", productId, newStock);
        logStore.info(SVC, traceId, "Deduction complete for " + productId + " newStock=" + newStock);

        return true;
    }

    /**
     * Release reserved stock (called on cancel/refund).
     * BUG: Not always called after payment failure — leaves reservedStock inconsistent.
     */
    public boolean releaseStock(String productId, int quantity, String traceId) {
        TraceContext.setService(SVC);
        TraceContext.bindTrace(traceId);

        log.info("Releasing reserved stock productId={} qty={}", productId, quantity);

        InventoryItem item = inventory.get(productId);
        if (item == null) {
            log.error("Cannot release stock — product not found: {}", productId);
            logStore.error(SVC, traceId, "RELEASE_PRODUCT_NOT_FOUND",
                    "Stock release failed silently for unknown product: " + productId);
            return false;
        }

        int restored = item.getStockRef().addAndGet(quantity);
        item.setReservedStock(Math.max(0, item.getReservedStock() - quantity));
        item.setLastUpdated(Instant.now());

        log.info("Stock released productId={} restoredTotal={}", productId, restored);
        logStore.info(SVC, traceId, "Stock released for " + productId + " restoredTo=" + restored);

        return true;
    }

    /**
     * Admin update endpoint.
     * BUG: Retried update double-adds stock (no idempotency).
     */
    public InventoryItem updateStock(String productId, int delta, String updatedBy, String traceId) {
        TraceContext.setService(SVC);
        TraceContext.bindTrace(traceId);

        log.info("Inventory update request productId={} delta={} by={}", productId, delta, updatedBy);
        logStore.info(SVC, traceId, "Admin stock update: " + productId + " delta=" + delta + " by=" + updatedBy);

        InventoryItem item = inventory.get(productId);
        if (item == null) {
            log.warn("Update on non-existent product — auto-creating: {}", productId);
            logStore.warn(SVC, traceId, "AUTO_CREATE_ON_UPDATE",
                    "Auto-creating inventory record for unknown productId=" + productId);
            item = new InventoryItem(productId, "Unknown Product", 0, 0.0);
            inventory.put(productId, item);
        }

        // Intentional: delta applied directly, no idempotency key check
        int newStock = item.getStockRef().addAndGet(delta);
        item.setLastUpdated(Instant.now());
        item.setLastUpdatedBy(updatedBy);

        if (newStock < 0) {
            log.error("Post-update stock is negative productId={} stock={}", productId, newStock);
            logStore.error(SVC, traceId, "POST_UPDATE_NEGATIVE_STOCK",
                    "Admin update caused negative stock for " + productId + ": " + newStock);
        }

        log.info("Inventory updated successfully productId={} newStock={}", productId, newStock);
        logStore.info(SVC, traceId, "Stock updated to " + newStock + " for " + productId);

        return item;
    }

    // Async post-deduction audit — loses trace_id (no MDC propagation to async thread)
    @Async("taskExecutor")
    public CompletableFuture<Void> auditDeductionAsync(String productId, int quantity, String traceId) {
        // MDC is cleared in async thread — trace_id is "unknown" in these logs
        log.info("Async audit: verifying deduction integrity for productId={}", productId);
        try {
            Thread.sleep(500 + new Random().nextInt(1500));
        } catch (InterruptedException ignored) {}

        InventoryItem item = inventory.get(productId);
        if (item != null && item.getStock() < 0) {
            log.error("AUDIT: Negative stock detected productId={} stock={}", productId, item.getStock());
            logStore.error(SVC, "ORPHANED-" + traceId, "AUDIT_NEGATIVE_STOCK",
                    "Post-deduction audit found negative stock for " + productId);
        } else {
            log.info("Async audit passed for productId={}", productId);
        }

        return CompletableFuture.completedFuture(null);
    }

    // Scheduled noise logger — makes RCA harder
    @Scheduled(fixedDelay = 45000)
    public void scheduledInventoryHealthCheck() {
        log.info("Scheduled inventory health check running");
        inventory.forEach((id, item) -> {
            if (item.getStock() < 5) {
                log.warn("Low stock alert productId={} stock={}", id, item.getStock());
                logStore.warn(SVC, "SCHEDULED", "LOW_STOCK_ALERT",
                        "Scheduled check: low stock for " + id + " remaining=" + item.getStock());
            }
        });
        log.info("Inventory health check complete items_checked={}", inventory.size());
    }

    private boolean shouldFail() {
        return Math.random() < failureRate;
    }
}
