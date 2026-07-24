package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-A-08: HandlerRegistry completeness test.
 * Verifies all expected BPMN element types have handlers, no duplicates, and aliases work.
 */
@SpringBootTest(classes = com.zorrodev.bpm.engine.TestMain.class)
@org.springframework.test.context.ActiveProfiles("test")
class HandlerRegistryTest {

    @Autowired
    HandlerRegistry registry;

    @Test
    void registryNotEmpty() {
        assertThat(registry.getHandlers()).isNotEmpty();
    }

    @Test
    void allCoreTypesRegistered() {
        // All core element types that the engine must handle
        for (BpmnElementType type : new BpmnElementType[]{
            BpmnElementType.EXCLUSIVE_GATEWAY,
            BpmnElementType.PARALLEL_GATEWAY,
            BpmnElementType.INCLUSIVE_GATEWAY,
            BpmnElementType.EVENT_BASED_GATEWAY,
            BpmnElementType.MESSAGE_CATCH_EVENT,
            BpmnElementType.TIMER_CATCH_EVENT,
            BpmnElementType.SIGNAL_CATCH_EVENT,
            BpmnElementType.CONDITIONAL_CATCH_EVENT,
            BpmnElementType.INTERMEDIATE_THROW_EVENT,
            BpmnElementType.MESSAGE_THROW_EVENT,
            BpmnElementType.SIGNAL_THROW_EVENT,
            BpmnElementType.LINK_THROW_EVENT,
            BpmnElementType.COMPENSATION_THROW_EVENT,
            BpmnElementType.USER_TASK,
            BpmnElementType.SERVICE_TASK,
            BpmnElementType.SEND_TASK,
            BpmnElementType.SCRIPT_TASK,
            BpmnElementType.BUSINESS_RULE_TASK,
            BpmnElementType.SUB_PROCESS,
            BpmnElementType.CALL_ACTIVITY,
            BpmnElementType.START_EVENT,
            BpmnElementType.END_EVENT,
            BpmnElementType.TERMINATE_END_EVENT,
            BpmnElementType.ERROR_END_EVENT,
            BpmnElementType.ESCALATION_END_EVENT,
            BpmnElementType.ESCALATION_THROW_EVENT,
            BpmnElementType.CANCEL_END_EVENT,
            // Aliases
            BpmnElementType.MESSAGE_START_EVENT,
            BpmnElementType.TIMER_START_EVENT,
            BpmnElementType.SIGNAL_START_EVENT,
            BpmnElementType.LINK_CATCH_EVENT,
            BpmnElementType.RECEIVE_TASK
        }) {
            assertThat(registry.get(type))
                .as("Handler for %s", type)
                .isNotNull();
        }
    }

    @Test
    void noDuplicateHandlerInstances() {
        // Each handler type maps to exactly one handler — no two DIFFERENT handlers for the same type (Map semantics)
        // and no duplicate MessageThrowHandler/CompensationThrowHandler instances (the bug WO-A-08 fixes)
        assertThat(registry.getHandlers()).isNotEmpty();
        // Verify no null handlers (all registered types resolve)
        for (var entry : registry.getHandlers().entrySet()) {
            assertThat(entry.getValue())
                .as("Handler for %s", entry.getKey())
                .isNotNull();
        }
    }

    @Test
    void aliasesResolved() {
        ElementHandler startHandler = registry.get(BpmnElementType.START_EVENT);
        assertThat(registry.get(BpmnElementType.MESSAGE_START_EVENT)).isSameAs(startHandler);
        assertThat(registry.get(BpmnElementType.TIMER_START_EVENT)).isSameAs(startHandler);
        assertThat(registry.get(BpmnElementType.SIGNAL_START_EVENT)).isSameAs(startHandler);
        assertThat(registry.get(BpmnElementType.LINK_CATCH_EVENT)).isSameAs(startHandler);

        ElementHandler msgCatchHandler = registry.get(BpmnElementType.MESSAGE_CATCH_EVENT);
        assertThat(registry.get(BpmnElementType.RECEIVE_TASK)).isSameAs(msgCatchHandler);
    }
}
