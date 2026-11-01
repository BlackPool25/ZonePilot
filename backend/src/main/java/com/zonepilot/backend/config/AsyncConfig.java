package com.zonepilot.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for the background breach worker: pgRouting reroutes must
 * never run on ingest threads (a failed road query aborts the ingest txn).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("breachExecutor")
    public Executor breachExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("breach-worker-");
        executor.initialize();
        return executor;
    }
}
