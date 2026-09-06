package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventTriggerTest {

    @Mock
    private DBService dbService;
    @Mock
    private BpmnService bpmnService;
    @Mock
    private ScriptService scriptService;
    @Mock
    private FlowNavigator flowNavigator;
    @Mock
    private ElementSupport elementSupport;
    @Mock
    private TimerJobRepository timerJobRepository;
    // WO-C8-28: новый конструкторный параметр (инжект моком — существующие тесты
    // путей без отмены его не трогают, но @InjectMocks без мока дал бы null).
    @Mock
    private CancelingPhaseService cancelingPhaseService;

    @InjectMocks
    private EventTrigger eventTrigger;

    @BeforeEach
    void setUp() {
        // @Value field — not injected by Mockito; the re-arm uses it for cycle resolution
        ReflectionTestUtils.setField(eventTrigger, "businessZone", ZoneId.of("UTC"));
    }

    @Test
    void conditionHolds_returnsTrueWhenConditionEvaluatesToTrue() {
        // Given
        BpmnElementModel element = new BpmnElementModel();
        element.setId("conditionalEvent1");
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        EventDefinitionExtensionModel eventDef = new EventDefinitionExtensionModel();
        eventDef.setExpression("= amount > 1000");
        ext.setEventDefinition(eventDef);
        element.setExtensions(ext);

        List<ProcessVariable> variables = List.of();
        when(scriptService.evaluateScript(" amount > 1000", variables)).thenReturn(true);

        // When
        boolean result = eventTrigger.conditionHolds(element, variables);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void conditionHolds_returnsFalseWhenConditionEvaluatesToFalse() {
        // Given
        BpmnElementModel element = new BpmnElementModel();
        element.setId("conditionalEvent1");
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        EventDefinitionExtensionModel eventDef = new EventDefinitionExtensionModel();
        eventDef.setExpression("= amount > 1000");
        ext.setEventDefinition(eventDef);
        element.setExtensions(ext);

        List<ProcessVariable> variables = List.of();
        when(scriptService.evaluateScript(" amount > 1000", variables)).thenReturn(false);

        // When
        boolean result = eventTrigger.conditionHolds(element, variables);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void conditionHolds_throwsWhenNoExpression() {
        // Given
        BpmnElementModel element = new BpmnElementModel();
        element.setId("conditionalEvent1");
        // No extensions

        // When & Then
        assertThatThrownBy(() -> eventTrigger.conditionHolds(element, List.of()))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("has no condition");
    }

    @Test
    void findConditionalBoundaries_returnsMatchingBoundaries() {
        // Given
        BpmnProcessDefinitionModel pd = new BpmnProcessDefinitionModel();

        BpmnElementModel host = new BpmnElementModel();
        host.setId("serviceTask1");
        host.setType(BpmnElementType.SERVICE_TASK);
        pd.addElement(host);

        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("conditionalBoundary1");
        boundary.setType(BpmnElementType.CONDITIONAL_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("serviceTask1");
        ext.setBoundaryEventExtension(boundaryExt);
        boundary.setExtensions(ext);
        pd.addElement(boundary);

        // When
        List<BpmnElementModel> result = eventTrigger.findConditionalBoundaries(pd, "serviceTask1");

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("conditionalBoundary1");
    }

    @Test
    void findConditionalBoundaries_ignoresUnattachedBoundaries() {
        // Given
        BpmnProcessDefinitionModel pd = new BpmnProcessDefinitionModel();

        BpmnElementModel host = new BpmnElementModel();
        host.setId("serviceTask1");
        host.setType(BpmnElementType.SERVICE_TASK);
        pd.addElement(host);

        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("conditionalBoundary1");
        boundary.setType(BpmnElementType.CONDITIONAL_BOUNDARY_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("serviceTask2"); // Different host
        ext.setBoundaryEventExtension(boundaryExt);
        boundary.setExtensions(ext);
        pd.addElement(boundary);

        // When
        List<BpmnElementModel> result = eventTrigger.findConditionalBoundaries(pd, "serviceTask1");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void repeatingBoundaryTimer_withoutPersistedExpression_endsCycleInsteadOfRearmingForever() {
        // WO-REL-17 criterion #4: a fired boundary timer job WITHOUT a persisted expression
        // (pre-REL-14/REL-17 row) must NOT be re-armed — the cycle ends with a warning, exactly
        // like TimerJobExecutor treats such catch-timer rows. Re-arming it would either burst
        // (zero-interval) or repeat forever.
        UUID hostActivityId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();

        Activity host = new Activity();
        host.setStatus(ActivityStatus.IN_PROGRESS);
        host.setProcessInstanceId(processInstanceId);
        host.setToken(tokenId);
        host.setBpmnElementId("host");
        when(elementSupport.lockAndReload(hostActivityId)).thenReturn(host);

        ProcessInstance processInstance = new ProcessInstance();
        processInstance.setProcessDefinitionId(processDefinitionId);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(processInstance);

        BpmnElementModel boundary = new BpmnElementModel();
        boundary.setId("tmrCheck");
        boundary.setType(BpmnElementType.BOUNDARY_TIMER_EVENT);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        BoundaryEventExtensionModel boundaryExt = new BoundaryEventExtensionModel();
        boundaryExt.setAttachedToRef("host");
        boundaryExt.setInterrupting(false);
        ext.setBoundaryEventExtension(boundaryExt);
        TimerEventExtensionModel timerExt = new TimerEventExtensionModel();
        timerExt.setType(TimerEventType.CYCLE);
        timerExt.setExpression("R3/PT1S");
        ext.setTimerEventExtension(timerExt);
        boundary.setExtensions(ext);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(boundary);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);

        when(dbService.createToken(tokenId)).thenReturn(new Token());

        // The fired job exists but carries NO persisted expression (pre-REL-14/REL-17 row)
        TimerJobEntity firedJob = new TimerJobEntity();
        firedJob.setId(UUID.randomUUID());
        firedJob.setActivityId(hostActivityId);
        firedJob.setDueAt(Instant.now());
        firedJob.setFired(true);
        firedJob.setBoundaryElementId("tmrCheck");
        firedJob.setRemainingCount(2);
        firedJob.setExpression(null);
        when(timerJobRepository.findFirstByActivityIdAndBoundaryElementIdAndFiredTrueOrderByCreatedAtDesc(
            hostActivityId, "tmrCheck")).thenReturn(Optional.of(firedJob));

        // When
        boolean fired = eventTrigger.fireBoundary(hostActivityId, "tmrCheck", List.of(),
            org.mockito.Mockito.mock(com.zorrodev.bpm.engine.handler.TokenExecutor.class));

        // Then: the boundary fired (host was active) but the cycle was NOT re-armed
        assertThat(fired).isTrue();
        verify(timerJobRepository).findFirstByActivityIdAndBoundaryElementIdAndFiredTrueOrderByCreatedAtDesc(
            hostActivityId, "tmrCheck");
        verify(dbService, never()).createTimerJob(any(), any(), any(), any(), any(), any());
    }
}