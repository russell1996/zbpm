package com.zorrodev.bpm.engine.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * WO-PERF-6: dedicated executors for timer batch processing (P-1).
 * {@code timerExecutor} 4-8 threads handles per-job parallelism ONLY.
 * {@code timerDispatcherExecutor} 1-2 threads offloads {@code processBatch()}
 * from the global scheduling pool (size 4, also used by Outbox/Watchdog).
 * Separation avoids self-consumption (dispatcher blocked on join consuming its
 * own pool) and isolates timer drift from Outbox pressure.
 */
@Configuration
public class TimerExecutorConfig {

    @Bean(name = "timerExecutor")
    public Executor timerExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("timer-batch-");
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(200);
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }

    @Bean(name = "timerDispatcherExecutor")
    public Executor timerDispatcherExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("timer-dispatcher-");
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(10);
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }
}
