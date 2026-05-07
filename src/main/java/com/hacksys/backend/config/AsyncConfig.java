package com.hacksys.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * Intentional: small core pool with no MDC propagation.
     * Async tasks lose MDC context (trace_id, service name) causing orphaned log entries.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("hacksys-async-");
        // Intentional: no MDC task decorator — async threads have no trace context
        executor.initialize();
        return executor;
    }
}
