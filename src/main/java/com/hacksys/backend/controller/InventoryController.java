package com.hacksys.backend.controller;

import com.hacksys.backend.model.InventoryItem;
import com.hacksys.backend.service.InventoryService;
import com.hacksys.backend.util.LogStore;
import com.hacksys.backend.util.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);
    private static final String SVC = "InventoryService";

    private final InventoryService inventoryService;
    private final LogStore logStore;

    public InventoryController(InventoryService inventoryService, LogStore logStore) {
        this.inventoryService = inventoryService;
        this.logStore = logStore;
    }

    /**
     * GET /inventory
     */
    @GetMapping
    public ResponseEntity<?> getInventory() {
        String traceId = TraceContext.initTrace();
        TraceContext.setService(SVC);

        log.info("GET /inventory received");
        try {
            return ResponseEntity.ok(inventoryService.getAllInventory());
        } finally {
            TraceContext.clearTrace();
        }
    }

    /**
     * POST /inventory/update
     * Body: { "productId": "...", "delta": N, "updatedBy": "..." }
     */
    @PostMapping("/update")
    public ResponseEntity<?> updateStock(@RequestBody Map<String, Object> body) {
        String traceId = TraceContext.initTrace();
        TraceContext.setService(SVC);

        log.info("POST /inventory/update received");
        logStore.info(SVC, traceId, "Stock update request received");

        try {
            String productId = (String) body.get("productId");
            int delta = body.containsKey("delta") ? ((Number) body.get("delta")).intValue() : 0;
            String updatedBy = (String) body.get("updatedBy");

            if (productId == null || productId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "productId required", "trace_id", traceId));
            }

            InventoryItem item = inventoryService.updateStock(productId, delta, updatedBy, traceId);
            return ResponseEntity.ok(item);
        } catch (Exception e) {
            log.error("POST /inventory/update error: {}", e.getMessage(), e);
            logStore.error(SVC, traceId, "UPDATE_FAILED", "Inventory update failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal error", "trace_id", traceId));
        } finally {
            TraceContext.clearTrace();
        }
    }
}
