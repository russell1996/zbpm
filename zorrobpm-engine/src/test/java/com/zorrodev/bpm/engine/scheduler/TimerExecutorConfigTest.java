package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.TestMain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-QW-2 criterion 5: {@code timerExecutor} queue capacity is configurable
 * and overflow applies back-pressure instead of rejection.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = TestMain.class,
    properties = "zorrobpm.timer.queue-capacity=321")
class TimerExecutorConfigTest {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("timerExecutor")
    java.util.concurrent.Executor timerExecutor;

    @Test
    void queueCapacity_isConfigurable() {
        assertThat(timerExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        assertThat(((ThreadPoolTaskExecutor) timerExecutor).getQueueCapacity()).isEqualTo(321);
    }

    @Test
    void overflowPolicy_isCallerRuns() {
        ThreadPoolTaskExecutor ex = (ThreadPoolTaskExecutor) timerExecutor;
        RejectedExecutionHandler handler = ex.getThreadPoolExecutor().getRejectedExecutionHandler();
        assertThat(handler).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }
}
