package com.hacksys.backend.scheduler;

import com.hacksys.backend.model.Order;
import com.hacksys.backend.service.InventoryService;
import com.hacksys.backend.service.OrderService;
import com.hacksys.backend.service.PaymentService;
import com.hacksys.backend.util.LogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ChaosScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChaosScheduler.class);
    private static final String SVC = "ChaosScheduler";

    private final LogStore logStore;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final InventoryService inventoryService;
    private final Random random = new Random();

    private static final String[] USER_IDS = {
        "user-101", "user-202", "user-303", "user-404", "user-505"
    };
    private static final String[] PRODUCT_IDS = {
        "PROD-001", "PROD-002", "PROD-003", "PROD-004", "PROD-005"
    };

    public ChaosScheduler(LogStore logStore, OrderService orderService,
                          PaymentService paymentService, InventoryService inventoryService) {
        this.logStore = logStore;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.inventoryService = inventoryService;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    public void simulateOrderFlow() {
        String traceId = "chaos-" + UUID.randomUUID().toString().substring(0, 8);
        String userId = USER_IDS[random.nextInt(USER_IDS.length)];
        String productId = PRODUCT_IDS[random.nextInt(PRODUCT_IDS.length)];
        int quantity = 1 + random.nextInt(5);

        logStore.info(SVC, traceId, "Chaos: starting simulated order flow for userId=" + userId);
        try {
            List<Order.OrderItem> items = List.of(new Order.OrderItem(productId, quantity, 79.99));
            Order order = orderService.createOrder(userId, items, traceId);

            if (random.nextDouble() < 0.3) {
                try {
                    paymentService.processPayment(order.getId(), userId, quantity * 79.99, traceId);
                } catch (RuntimeException e) {
                    logStore.error(SVC, traceId, "CHAOS_PAYMENT_FAILED",
                        "Chaos: payment failed for orderId=" + order.getId() + " reason=" + e.getMessage());
                }
            }

            if (random.nextDouble() < 0.2) {
                try {
                    orderService.cancelOrder(order.getId(), traceId);
                    logStore.warn(SVC, traceId, "CHAOS_CANCEL_AFTER_CREATE",
                        "Chaos: order cancelled shortly after creation orderId=" + order.getId());
                } catch (Exception e) {
                    logStore.error(SVC, traceId, "CHAOS_CANCEL_FAILED",
                        "Chaos: cancellation threw exception orderId=" + order.getId());
                }
            }
        } catch (RuntimeException e) {
            logStore.error(SVC, traceId, "CHAOS_ORDER_FLOW_FAILED",
                "Chaos order flow failed: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 45000, initialDelay = 12000)
    public void simulateNullUserIdBug() {
        String traceId = "chaos-null-" + UUID.randomUUID().toString().substring(0, 8);
        logStore.warn(SVC, traceId, "NULL_USER_SCENARIO",
            "Chaos: simulating order with null userId — expect NPE downstream");
        try {
            List<Order.OrderItem> items = List.of(new Order.OrderItem("PROD-001", 1, 79.99));
            orderService.createOrder(null, items, traceId);
        } catch (Exception e) {
            logStore.error(SVC, traceId, "NULL_POINTER_EXCEPTION",
                "Chaos: NullPointerException triggered by null userId — class=OrderService method=createOrder");
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 20000)
    public void simulateStateMachineBug() {
        String traceId = "chaos-state-" + UUID.randomUUID().toString().substring(0, 8);
        logStore.warn(SVC, traceId, "STATE_MACHINE_SCENARIO",
            "Chaos: simulating payment on a CANCELLED order — state machine violation expected");
        try {
            List<Order.OrderItem> items = List.of(new Order.OrderItem("PROD-002", 1, 129.99));
            Order order = orderService.createOrder("user-chaos", items, traceId);
            orderService.cancelOrder(order.getId(), traceId);
            try {
                paymentService.processPayment(order.getId(), "user-chaos", 129.99, traceId);
                logStore.error(SVC, traceId, "PAYMENT_ON_CANCELLED_ORDER",
                    "CRITICAL: Payment accepted for CANCELLED order! orderId=" + order.getId() +
                    " — state machine violation, payment should have been rejected.");
            } catch (RuntimeException e) {
                logStore.warn(SVC, traceId, "PAYMENT_GATEWAY_TIMEOUT",
                    "Chaos: gateway timeout during payment on cancelled order: " + e.getMessage());
            }
        } catch (Exception e) {
            logStore.error(SVC, traceId, "CHAOS_STATE_MACHINE_ERROR",
                "Chaos state machine scenario threw: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 50000, initialDelay = 8000)
    public void simulateNegativeInventoryBug() {
        String traceId = "chaos-inv-" + UUID.randomUUID().toString().substring(0, 8);
        logStore.warn(SVC, traceId, "NEGATIVE_STOCK_SCENARIO",
            "Chaos: deducting large quantity to force negative stock — boundary error expected");
        try {
            inventoryService.deductStock("PROD-005", 999, traceId);
            logStore.error(SVC, traceId, "NEGATIVE_STOCK_DETECTED",
                "Chaos: stock for PROD-005 is now negative! Root cause: deductStock() does not validate quantity before deducting.");
        } catch (Exception e) {
            logStore.error(SVC, traceId, "CHAOS_INVENTORY_ERROR",
                "Chaos inventory scenario threw: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 40000, initialDelay = 15000)
    public void simulateDuplicatePaymentBug() {
        String traceId = "chaos-dup-" + UUID.randomUUID().toString().substring(0, 8);
        logStore.warn(SVC, traceId, "DUPLICATE_PAYMENT_SCENARIO",
            "Chaos: retrying payment for same order — duplicate charge expected");
        try {
            List<Order.OrderItem> items = List.of(new Order.OrderItem("PROD-003", 2, 39.99));
            Order order = orderService.createOrder("user-retry", items, traceId);
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    paymentService.processPayment(order.getId(), "user-retry", 79.98, traceId);
                    if (attempt == 2) {
                        logStore.error(SVC, traceId, "DUPLICATE_CHARGE",
                            "Chaos: DUPLICATE PAYMENT PROCESSED for orderId=" + order.getId() +
                            " attempt=" + attempt + " — no idempotency key in PaymentService!");
                    }
                } catch (RuntimeException e) {
                    logStore.warn(SVC, traceId, "PAYMENT_ATTEMPT_FAILED",
                        "Chaos: payment attempt=" + attempt + " failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logStore.error(SVC, traceId, "CHAOS_DUPLICATE_PAYMENT_ERROR",
                "Chaos duplicate payment scenario threw: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 35000, initialDelay = 3000)
    public void emitNoiseLogs() {
        String traceId = "noise-" + UUID.randomUUID().toString().substring(0, 8);
        String[] noisy = {
            "Scheduled task heartbeat — all systems nominal",
            "Connection pool health check passed — 8/10 connections active",
            "Cache eviction triggered — 142 entries removed",
            "Metrics flush complete — sent 38 data points to collector",
            "Token refresh cycle complete — next refresh in 3600s",
            "Background audit complete — 0 anomalies detected"
        };
        String[] warnings = {
            "Response time spike detected — p99=2340ms (threshold=2000ms)",
            "Thread pool utilization high — 7/8 threads busy",
            "Slow query detected — took 890ms on orders table",
            "Memory usage at 78% — GC pressure increasing"
        };
        for (int i = 0; i < 2 + random.nextInt(3); i++) {
            logStore.info(SVC, traceId, noisy[random.nextInt(noisy.length)]);
        }
        if (random.nextDouble() < 0.4) {
            logStore.warn(SVC, traceId, "PERFORMANCE_DEGRADATION",
                warnings[random.nextInt(warnings.length)]);
        }
    }
}