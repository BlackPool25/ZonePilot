package com.zonepilot.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded executor for the background breach worker: pgRouting reroutes must
 * never run on ingest threads (a failed road query aborts the ingest txn).
 * Breach work is best-effort: on saturation the task is discarded (never run
 * on the caller — that would stall ingest), counted, and warned.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);
    static final AtomicLong rejectedTasks = new AtomicLong();

    /** Discarded-task count since boot (saturation signal for dashboards). */
    public static long rejectedCount() {
        return rejectedTasks.get();
    }

    @Bean("breachExecutor")
    public Executor breachExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler((task, exe) -> {
            long n = rejectedTasks.incrementAndGet();
            log.warn("breach-executor saturated, discarding task (discarded={})", n);
        });
        executor.setThreadNamePrefix("breach-worker-");
        executor.initialize();
        return executor;
    }
}
