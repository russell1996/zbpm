package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.handler.FlowNavigator;
import com.zorrodev.bpm.engine.handler.MultiInstanceExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-4 (A3): {@code ActivityServiceImpl} and {@code MultiInstanceExecutor}
 * must share the single Spring {@link FlowNavigator} bean — not {@code new} one
 * each in {@code @PostConstruct}. Before the fix both held private instances;
 * this test pins the injected identity.
 */
@ActiveProfiles("test")
@SpringBootTest
class FlowNavigatorInjectionTest {

    @Autowired private ApplicationContext context;
    @Autowired private ActivityServiceImpl activityService;
    @Autowired private MultiInstanceExecutor multiInstanceExecutor;

    @Test
    void flowNavigator_isSharedSingletonBean() {
        FlowNavigator bean = context.getBean(FlowNavigator.class);
        Object inActivity = ReflectionTestUtils.getField(activityService, "flowNavigator");
        Object inMi = ReflectionTestUtils.getField(multiInstanceExecutor, "flowNavigator");
        assertThat(inActivity).as("ActivityServiceImpl.flowNavigator").isSameAs(bean);
        assertThat(inMi).as("MultiInstanceExecutor.flowNavigator").isSameAs(bean);
    }
}
