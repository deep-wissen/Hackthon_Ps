package com.hacksys.backend.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * MdcTaskDecorator — captures the caller thread's full MDC context map and
     * restores it on the async executor thread before the task runs, then clears
     * it afterwards.  This ensures trace_id / service / order_id survive the
     * hand-off to the thread pool (fix for INC-20260510054703-1A7748:
     * CONFIRM_NOTIFICATION_FAILED — missing MDC propagation).
     */
    private static class MdcTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            // Capture the MDC context from the submitting (caller) thread
            Map<String, String> callerMdc = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    // Restore caller MDC on the async executor thread
                    if (callerMdc != null) {
                        MDC.setContextMap(callerMdc);
                    } else {
                        MDC.clear();
                    }
                    runnable.run();
                } finally {
                    // Prevent MDC context from leaking to the next task on this thread
                    MDC.clear();
                }
            };
        }
    }

    /**
     * Fix INC-20260510054703-1A7748:
     *  1. Attach MdcTaskDecorator so every async task inherits the caller's trace context.
     *  2. Increase pool capacity (core=10, max=30, queue=200) to prevent executor
     *     exhaustion under confirmation burst load and avoid RejectedExecutionException.
     *  3. Set keep-alive so idle threads above core size are reclaimed after 60 s.
     *  4. Set CallerRunsPolicy as rejection handler so that if the queue still fills,
     *     the caller executes the task itself rather than dropping it silently.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Increased core/max pool and queue to handle payment confirmation burst (was 3/8/50)
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);  // reclaim idle threads above core size
        executor.setThreadNamePrefix("hacksys-async-");
        // Propagate full MDC context to every async task (idempotency-key header included)
        executor.setTaskDecorator(new MdcTaskDecorator());
        // CallerRunsPolicy: if queue is still full, run in caller thread rather than drop
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
