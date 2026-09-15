package com.zorrodev.bpm.engine.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * WO-PERF-6: dedicated executor for timer batch processing (P-1).
 * 4-8 threads, not shared with Outbox (2s poll) / Watchdog (60s) which run on
 * the global scheduling pool (size 4). Prevents timer drift under load.
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
}
