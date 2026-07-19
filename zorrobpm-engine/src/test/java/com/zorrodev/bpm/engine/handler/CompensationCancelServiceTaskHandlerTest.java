package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Mini-registration test (V5 + test:gate) for the three handlers extracted in WO-AUD-21:
 * CompensationThrowHandler, CancelEndHandler, ServiceTaskHandler.
 */
@ExtendWith(MockitoExtension.class)
class CompensationCancelServiceTaskHandlerTest {

    @Mock private DBService dbService;
    @Mock private FlowNavigator flowNavigator;
    @Mock private ActivityService activityService;
    @Mock private ElementSupport elementSupport;
    @Mock private MultiInstanceExecutor multiInstanceExecutor;
    @Mock private ServiceTaskEnqueueService serviceTaskEnqueueService;

    @Test
    void compensationThrowHandler_elementTypeAndHandler() {
        CompensationThrowHandler handler = new CompensationThrowHandler(dbService, flowNavigator);
        assertEquals(BpmnElementType.COMPENSATION_THROW_EVENT, handler.elementType());
        assertSame(handler, handler.handler());
        assertNotNull(handler);
    }

    @Test
    void cancelEndHandler_elementTypeAndHandler() {
        CompensationThrowHandler compHandler = new CompensationThrowHandler(dbService, flowNavigator);
        CancelEndHandler handler = new CancelEndHandler(dbService, flowNavigator, activityService, compHandler);
        assertEquals(BpmnElementType.CANCEL_END_EVENT, handler.elementType());
        assertSame(handler, handler.handler());
    }

    @Test
    void serviceTaskHandler_elementTypeAndHandler() {
        ServiceTaskHandler handler = new ServiceTaskHandler(dbService, elementSupport, multiInstanceExecutor, serviceTaskEnqueueService);
        assertEquals(BpmnElementType.SERVICE_TASK, handler.elementType());
        assertSame(handler, handler.handler());
    }
}
