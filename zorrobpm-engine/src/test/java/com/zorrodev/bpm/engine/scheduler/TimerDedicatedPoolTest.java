package com.zorrodev.bpm.engine.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-PERF-6 P-1: timer batch must run on dedicated executor, not shared scheduling pool.
 */
@SpringBootTest
@ActiveProfiles("test")
class TimerDedicatedPoolTest {

    @Autowired private TimerBatchProcessor batchProcessor;
    @Autowired private TimerScheduler scheduler;
    @Autowired private org.springframework.context.ApplicationContext ctx;

    @Test
    void timerBatchProcessor_usesDedicatedExecutor() throws Exception {
        Field f = TimerBatchProcessor.class.getDeclaredField("timerExecutor");
        f.setAccessible(true);
        Executor timerExec = (Executor) f.get(batchProcessor);
        assertThat(timerExec).isNotNull();
        // scheduling pool is ThreadPoolTaskScheduler, dedicated is ThreadPoolTaskExecutor
        assertThat(timerExec.getClass().getSimpleName()).contains("ThreadPoolTaskExecutor");
    }

    @Test
    void timerScheduler_usesDedicatedExecutor() throws Exception {
        Field f = TimerScheduler.class.getDeclaredField("timerDispatcherExecutor");
        f.setAccessible(true);
        Executor timerExec = (Executor) f.get(scheduler);
        assertThat(timerExec).isNotNull();
        assertThat(ctx.containsBean("timerDispatcherExecutor")).isTrue();
    }

    @Test
    void timerExecutor_beanExists() {
        assertThat(ctx.containsBean("timerExecutor")).isTrue();
    }
}
