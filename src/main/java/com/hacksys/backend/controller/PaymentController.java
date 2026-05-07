package com.hacksys.backend.controller;

import com.hacksys.backend.model.Payment;
import com.hacksys.backend.service.PaymentService;
import com.hacksys.backend.util.LogStore;
import com.hacksys.backend.util.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private static final String SVC = "PaymentService";

    private final PaymentService paymentService;
    private final LogStore logStore;

    public PaymentController(PaymentService paymentService, LogStore logStore) {
        this.paymentService = paymentService;
        this.logStore = logStore;
    }

    /**
     * POST /pay
     * Body: { "orderId": "...", "userId": "...", "amount": N }
     *
     * Intentional: No idempotency header required — retries cause duplicate charges.
     */
    @PostMapping("/pay")
    public ResponseEntity<?> pay(@RequestBody Map<String, Object> body) {
        String traceId = TraceContext.initTrace();
        TraceContext.setService(SVC);

        log.info("POST /pay received");
        logStore.info(SVC, traceId, "POST /pay request received");

        try {
            String orderId = (String) body.get("orderId");
            String userId  = (String) body.get("userId");  // can be null
            double amount  = body.containsKey("amount") ? ((Number) body.get("amount")).doubleValue() : 0.0;

            if (orderId == null || orderId.isBlank()) {
                logStore.error(SVC, traceId, "MISSING_ORDER_ID", "POST /pay missing orderId");
                return ResponseEntity.badRequest().body(Map.of("error", "orderId required", "trace_id", traceId));
            }

            // Noise: amount=0 is suspicious but not blocked
            if (amount == 0.0) {
                log.warn("POST /pay called with amount=0 orderId={}", orderId);
                logStore.warn(SVC, traceId, "ZERO_AMOUNT_PAYMENT",
                        "Payment initiated with amount=0 for orderId=" + orderId);
            }

            Payment payment = paymentService.processPayment(orderId, userId, amount, traceId);

            log.info("POST /pay success paymentId={}", payment.getId());
            return ResponseEntity.ok(Map.of(
                    "paymentId", payment.getId(),
                    "status", payment.getStatus().name(),
                    "orderId", orderId,
                    "trace_id", traceId
            ));

        } catch (IllegalArgumentException e) {
            log.warn("POST /pay bad request: {}", e.getMessage());
            logStore.warn(SVC, traceId, "BAD_REQUEST", "Payment rejected: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage(), "trace_id", traceId));
        } catch (RuntimeException e) {
            // Gateway timeout — client will retry — which causes duplicate charge
            log.warn("POST /pay gateway error (retriable): {}", e.getMessage());
            logStore.warn(SVC, traceId, "GATEWAY_ERROR",
                    "Payment gateway error for request — client should retry: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Payment service temporarily unavailable — please retry",
                            "trace_id", traceId));
        } finally {
            TraceContext.clearTrace();
        }
    }

    /**
     * POST /refund
     * Body: { "orderId": "..." }
     *
     * BUG: No guard against calling this twice — double refund possible.
     */
    @PostMapping("/refund")
    public ResponseEntity<?> refund(@RequestBody Map<String, Object> body) {
        String traceId = TraceContext.initTrace();
        TraceContext.setService(SVC);

        String orderId = (String) body.get("orderId");
        log.info("POST /refund received orderId={}", orderId);
        logStore.info(SVC, traceId, "POST /refund request for orderId=" + orderId);

        try {
            if (orderId == null || orderId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "orderId required", "trace_id", traceId));
            }

            Payment refund = paymentService.refundPayment(orderId, traceId);
            return ResponseEntity.ok(Map.of(
                    "paymentId", refund.getId(),
                    "status", refund.getStatus().name(),
                    "orderId", orderId,
                    "trace_id", traceId
            ));
        } catch (IllegalStateException e) {
            log.warn("POST /refund failed: {}", e.getMessage());
            logStore.warn(SVC, traceId, "REFUND_FAILED", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage(), "trace_id", traceId));
        } catch (Exception e) {
            log.error("POST /refund error: {}", e.getMessage(), e);
            logStore.error(SVC, traceId, "UNHANDLED_ERROR", "Refund error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal error", "trace_id", traceId));
        } finally {
            TraceContext.clearTrace();
        }
    }

    /**
     * GET /pay/history/{orderId} — list all payment attempts for an order.
     * Reveals duplicate charges when idempotency bug is triggered.
     */
    @GetMapping("/pay/history/{orderId}")
    public ResponseEntity<?> paymentHistory(@PathVariable String orderId) {
        String traceId = TraceContext.initTrace();
        try {
            List<Payment> payments = paymentService.getPaymentsForOrder(orderId);
            if (payments.size() > 1) {
                log.warn("Multiple payment records found orderId={} count={}", orderId, payments.size());
                logStore.warn(SVC, traceId, "DUPLICATE_PAYMENT_DETECTED",
                        "Multiple payments recorded for orderId=" + orderId + " count=" + payments.size());
            }
            return ResponseEntity.ok(Map.of("payments", payments, "trace_id", traceId));
        } finally {
            TraceContext.clearTrace();
        }
    }
}
