package com.hacksys.backend.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory log store.
 * Backs the /logs API endpoint.
 * Capped at 2000 entries — older entries silently dropped.
 */
@Component
public class LogStore {

    private static final Logger log = LoggerFactory.getLogger(LogStore.class);
    private static final int MAX_ENTRIES = 2000;
    private static final ObjectMapper mapper = new ObjectMapper();

    // Static so it survives bean re-creation during context refresh
    private static final CopyOnWriteArrayList<ObjectNode> entries = new CopyOnWriteArrayList<>();

    public void append(String level, String service, String traceId,
                       String errorType, String message, String className) {
        ObjectNode node = mapper.createObjectNode();
        node.put("timestamp", Instant.now().toString());
        node.put("trace_id", traceId != null ? traceId : "unknown");
        node.put("service", service != null ? service : "unknown");
        node.put("class", className != null ? className : "unknown");
        node.put("error_type", errorType != null ? errorType : "NONE");
        node.put("message", message);
        node.put("level", level);

        entries.add(node);

        // Silently drop oldest logs when cap exceeded
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    public List<ObjectNode> getAll() {
        return new ArrayList<>(entries);
    }

    public List<ObjectNode> getByTraceId(String traceId) {
        return entries.stream()
                .filter(e -> traceId.equals(e.path("trace_id").asText()))
                .collect(Collectors.toList());
    }

    public void clear() {
        entries.clear();
    }

    // Convenience shorthands used by services
    public void info(String service, String traceId, String message) {
    String caller = getCaller();
    append("INFO", service, traceId, null, message, caller);
}
    public void warn(String service, String traceId, String errorType, String message) {
    String caller = getCaller();
    append("WARN", service, traceId, errorType, message, caller);
}
    public void error(String service, String traceId, String errorType, String message) {
    String caller = getCaller();
    append("ERROR", service, traceId, errorType, message, caller);
}

    private String getCaller() {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    for (StackTraceElement el : stack) {
        String cls = el.getClassName();
        if (!cls.contains("LogStore") && !cls.contains("Thread") && cls.contains("hacksys")) {
            return cls.substring(cls.lastIndexOf('.') + 1);
        }
    }
    return "unknown";
}
}
