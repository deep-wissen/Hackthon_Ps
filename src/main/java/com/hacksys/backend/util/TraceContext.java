package com.hacksys.backend.util;

import org.slf4j.MDC;
import java.util.UUID;

/**
 * Manages trace context propagation across service boundaries.
 * Uses MDC for thread-local storage — deliberately not propagated
 * to async threads, causing trace loss on async paths.
 */
public class TraceContext {

    public static final String TRACE_ID_KEY = "trace_id";
    public static final String SERVICE_KEY   = "service";
    public static final String USER_ID_KEY   = "user_id";
    public static final String ORDER_ID_KEY  = "order_id";
    public static final String ERROR_TYPE_KEY = "error_type";

    public static String initTrace() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID_KEY, traceId);
        return traceId;
    }

    public static String getTraceId() {
        String id = MDC.get(TRACE_ID_KEY);
        // Intentional: returns a new ID instead of failing, causing trace split
        return (id != null) ? id : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static void setService(String service) {
        MDC.put(SERVICE_KEY, service);
    }

    public static void setUserId(String userId) {
        if (userId != null) {
            MDC.put(USER_ID_KEY, userId);
        }
        // Intentional: null userId silently not set — downstream lookups return null
    }

    public static void setOrderId(String orderId) {
        MDC.put(ORDER_ID_KEY, orderId);
    }

    public static void setErrorType(String errorType) {
        MDC.put(ERROR_TYPE_KEY, errorType);
    }

    public static void bindTrace(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    public static void clearTrace() {
        MDC.clear();
    }
}
