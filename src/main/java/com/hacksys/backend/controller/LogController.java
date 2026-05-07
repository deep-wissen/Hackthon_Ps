package com.hacksys.backend.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hacksys.backend.util.LogStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/logs")
public class LogController {

    private final LogStore logStore;

    public LogController(LogStore logStore) {
        this.logStore = logStore;
    }

    /**
     * GET /logs
     * Optional filter: ?trace_id=...
     */
    @GetMapping
    public List<ObjectNode> getLogs(@RequestParam(required = false) String trace_id) {
        if (trace_id != null && !trace_id.isBlank()) {
            return logStore.getByTraceId(trace_id);
        }
        return logStore.getAll();
    }
}
