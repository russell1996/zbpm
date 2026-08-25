package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.engine.handler.ExecutionContext;
import com.zorrodev.bpm.engine.handler.ElementHandler;
import com.zorrodev.bpm.engine.handler.HandlerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.zorrodev.bpm.contract.exception.EngineException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension .class)
public class ActivityServiceImplTests {

    private final BpmnParseService bpmnParseService = new BpmnParseServiceImpl();

    @Mock
    private DBService dbService;

    @Mock
    private BpmnService bpmnService;

    @Mock
    private ScriptService scriptService;

    @Mock
    private ServiceTaskEnqueueService serviceTaskEnqueueService;

    @Mock
    private DmnService dmnService;

    @Mock
    private ExecutionContext executionContext;

    @Mock
    private HandlerRegistry handlerRegistry;

    @Mock
    private com.zorrodev.bpm.engine.handler.MultiInstanceExecutor multiInstanceExecutor;

    @Mock
    private com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;

    private com.zorrodev.bpm.engine.handler.BoundaryScheduler boundaryScheduler;

    @InjectMocks
    private ActivityServiceImpl activityService;

    @BeforeEach
    void setUp() {
        // Create real BoundaryScheduler with mocked dependencies
        boundaryScheduler = new com.zorrodev.bpm.engine.handler.BoundaryScheduler(dbService, elementSupport);
        // Inject it into activityService via reflection (since @InjectMocks creates it before we have the real instance)
        try {
            var field = ActivityServiceImpl.class.getDeclaredField("boundaryScheduler");
            field.setAccessible(true);
            field.set(activityService, boundaryScheduler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Create real EventTrigger with mocked dependencies and inject it
        var flowNavigator = new com.zorrodev.bpm.engine.handler.FlowNavigator(dbService, bpmnService, scriptService, elementSupport);
        var eventTrigger = new com.zorrodev.bpm.engine.handler.EventTrigger(
            dbService, bpmnService, scriptService, flowNavigator, elementSupport,
            org.mockito.Mockito.mock(com.zorrodev.bpm.engine.repository.TimerJobRepository.class));
        try {
            var etField = ActivityServiceImpl.class.getDeclaredField("eventTrigger");
            etField.setAccessible(true);
            etField.set(activityService, eventTrigger);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Stub elementSupport.lockAndReload to delegate to dbService (it's a real method on ElementSupport)
        org.mockito.Mockito.lenient().when(elementSupport.lockAndReload(any(java.util.UUID.class))).thenAnswer(invocation -> {
            java.util.UUID activityId = invocation.getArgument(0);
            Activity act = dbService.getActivity(activityId);
            dbService.lockProcessInstance(act.getProcessInstanceId());
            return dbService.getActivity(activityId);
        });
        // Stub computeDueAt for the timer catch test (BPMN uses PT5M)
        org.mockito.Mockito.lenient().when(
            elementSupport.computeDueAt(any(com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel.class), any(java.util.UUID.class))
        ).thenAnswer(invocation -> java.time.Instant.now().plus(java.time.Duration.ofMinutes(5)));
        // Stub computeDueAt(timer, elementId, processInstanceId) for EventTrigger callers
        org.mockito.Mockito.lenient().when(
            elementSupport.computeDueAt(any(com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel.class), any(), any(java.util.UUID.class))
        ).thenAnswer(invocation -> java.time.Instant.now().plus(java.time.Duration.ofMinutes(5)));
        // Create real CompletionService with mocked dependencies and inject it (WO-AUD-24)
        var completionService = new com.zorrodev.bpm.engine.handler.CompletionService(
            dbService, bpmnService, serviceTaskEnqueueService, elementSupport, multiInstanceExecutor,
            flowNavigator, eventTrigger, executionContext);
        try {
            var csField = ActivityServiceImpl.class.getDeclaredField("completionService");
            csField.setAccessible(true);
            csField.set(activityService, completionService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Create real IncidentService with mocked dependencies and inject it (WO-AUD-25)
        var incidentService = new com.zorrodev.bpm.engine.handler.IncidentService(dbService);
        try {
            var isField = ActivityServiceImpl.class.getDeclaredField("incidentService");
            isField.setAccessible(true);
            isField.set(activityService, incidentService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Create real ErrorEscalationThrower with mocked dependencies and inject it (WO-AUD-23)
        var errorEscalationThrower = new com.zorrodev.bpm.engine.handler.ErrorEscalationThrower(dbService, bpmnService, flowNavigator, eventTrigger);
        try {
            var eetField = ActivityServiceImpl.class.getDeclaredField("errorEscalationThrower");
            eetField.setAccessible(true);
            eetField.set(activityService, errorEscalationThrower);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Simulate @PostConstruct — just initialize flowNavigator
        activityService.init();

        // WO-A-08: Register handlers on the mock HandlerRegistry so execute() can resolve them.
        // The real HandlerRegistry auto-discovers @Component handler beans via Spring DI;
        // in this Mockito unit test, we replicate that resolution manually.
        var flowNav = new com.zorrodev.bpm.engine.handler.FlowNavigator(dbService, bpmnService, scriptService, elementSupport);
        registerHandler(BpmnElementType.EXCLUSIVE_GATEWAY, new com.zorrodev.bpm.engine.handler.ExclusiveGatewayHandler(dbService, flowNav));
        registerHandler(BpmnElementType.PARALLEL_GATEWAY, new com.zorrodev.bpm.engine.handler.ParallelGatewayHandler(dbService, flowNav));
        registerHandler(BpmnElementType.INCLUSIVE_GATEWAY, new com.zorrodev.bpm.engine.handler.InclusiveGatewayHandler(dbService, flowNav, scriptService));
        registerHandler(BpmnElementType.EVENT_BASED_GATEWAY, new com.zorrodev.bpm.engine.handler.EventBasedGatewayHandler(dbService, flowNav));
        registerHandler(BpmnElementType.INTERMEDIATE_CATCH_EVENT, new com.zorrodev.bpm.engine.handler.WaitStateHandler(dbService));
        registerHandler(BpmnElementType.MESSAGE_CATCH_EVENT, new com.zorrodev.bpm.engine.handler.MessageCatchHandler(dbService, activityService));
        registerHandler(BpmnElementType.TIMER_CATCH_EVENT, new com.zorrodev.bpm.engine.handler.TimerCatchHandler(dbService, elementSupport));
        registerHandler(BpmnElementType.SIGNAL_CATCH_EVENT, new com.zorrodev.bpm.engine.handler.SignalCatchHandler(dbService));
        registerHandler(BpmnElementType.CONDITIONAL_CATCH_EVENT, new com.zorrodev.bpm.engine.handler.ConditionalCatchHandler(dbService, flowNav, scriptService));
        registerHandler(BpmnElementType.INTERMEDIATE_THROW_EVENT, new com.zorrodev.bpm.engine.handler.IntermediateThrowEventHandler(dbService, flowNav));
        registerHandler(BpmnElementType.MESSAGE_THROW_EVENT, new com.zorrodev.bpm.engine.handler.MessageThrowHandler(dbService, flowNav, activityService));
        registerHandler(BpmnElementType.SIGNAL_THROW_EVENT, new com.zorrodev.bpm.engine.handler.SignalThrowHandler(dbService, flowNav, activityService));
        registerHandler(BpmnElementType.LINK_THROW_EVENT, new com.zorrodev.bpm.engine.handler.LinkThrowHandler(dbService, activityService));
        registerHandler(BpmnElementType.SEND_TASK, new com.zorrodev.bpm.engine.handler.SendTaskHandler(activityService,
            new com.zorrodev.bpm.engine.handler.MessageThrowHandler(dbService, flowNav, activityService)));
        registerHandler(BpmnElementType.SUB_PROCESS, new com.zorrodev.bpm.engine.handler.SubProcessHandler(dbService));
        registerHandler(BpmnElementType.CALL_ACTIVITY, new com.zorrodev.bpm.engine.handler.CallActivityHandler(dbService, activityService, elementSupport));
        registerHandler(BpmnElementType.USER_TASK, new com.zorrodev.bpm.engine.handler.UserTaskHandler(dbService, elementSupport, multiInstanceExecutor, boundaryScheduler));
        registerHandler(BpmnElementType.START_EVENT, new com.zorrodev.bpm.engine.handler.StartThrowEventHandler.StartEvent(dbService, flowNav));
        registerHandler(BpmnElementType.SCRIPT_TASK, new com.zorrodev.bpm.engine.handler.SyncTaskHandler.ScriptTask(dbService, scriptService, elementSupport, flowNav, activityService));
        registerHandler(BpmnElementType.BUSINESS_RULE_TASK, new com.zorrodev.bpm.engine.handler.SyncTaskHandler.BusinessRuleTask(dbService, scriptService, dmnService, elementSupport, flowNav));
        registerHandler(BpmnElementType.END_EVENT, new com.zorrodev.bpm.engine.handler.EndEventHandler.EndEvent(dbService, activityService));
        registerHandler(BpmnElementType.TERMINATE_END_EVENT, new com.zorrodev.bpm.engine.handler.EndEventHandler.TerminateEndEvent(dbService));
        registerHandler(BpmnElementType.ERROR_END_EVENT, new com.zorrodev.bpm.engine.handler.EndEventHandler.ErrorEndEvent(dbService, activityService));
        registerHandler(BpmnElementType.ESCALATION_END_EVENT, new com.zorrodev.bpm.engine.handler.EndEventHandler.EscalationEndEvent(dbService, activityService));
        registerHandler(BpmnElementType.ESCALATION_THROW_EVENT, new com.zorrodev.bpm.engine.handler.StartThrowEventHandler.EscalationThrowEvent(dbService, flowNav, activityService));
        registerHandler(BpmnElementType.SERVICE_TASK, new com.zorrodev.bpm.engine.handler.ServiceTaskHandler(dbService, elementSupport, multiInstanceExecutor, serviceTaskEnqueueService));
        registerHandler(BpmnElementType.COMPENSATION_THROW_EVENT, new com.zorrodev.bpm.engine.handler.CompensationThrowHandler(dbService, flowNav));
        registerHandler(BpmnElementType.CANCEL_END_EVENT, new com.zorrodev.bpm.engine.handler.CancelEndHandler(dbService, flowNav, activityService,
            new com.zorrodev.bpm.engine.handler.CompensationThrowHandler(dbService, flowNav)));
        // Aliases
        registerHandler(BpmnElementType.MESSAGE_START_EVENT, handlerRegistry.get(BpmnElementType.START_EVENT));
        registerHandler(BpmnElementType.TIMER_START_EVENT, handlerRegistry.get(BpmnElementType.START_EVENT));
        registerHandler(BpmnElementType.SIGNAL_START_EVENT, handlerRegistry.get(BpmnElementType.START_EVENT));
        registerHandler(BpmnElementType.LINK_CATCH_EVENT, handlerRegistry.get(BpmnElementType.START_EVENT));
        registerHandler(BpmnElementType.RECEIVE_TASK, handlerRegistry.get(BpmnElementType.MESSAGE_CATCH_EVENT));
    }

    private void registerHandler(BpmnElementType type, ElementHandler handler) {
        org.mockito.Mockito.lenient().when(handlerRegistry.get(type)).thenReturn(handler);
    }

    @Test
    public void test1() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test1.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(1)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT);
    }

    @Test
    public void test2() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test2.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent2"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT);
    }

    @Test
    @Transactional
    @Rollback(false)
    public void test3() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test3.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID serviceTaskId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setType(BpmnElementType.SERVICE_TASK);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId("serviceTask1");
        activity.setToken(token);
        activity.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("serviceTask1"))).thenReturn(serviceTaskId);
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");
        activityService.completeServiceTask(serviceTaskId, List.of());

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.SERVICE_TASK);
    }

    @Test
    public void test4() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test4.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID userTaskId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setType(BpmnElementType.USER_TASK);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId("userTask1");
        activity.setToken(token);
        activity.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.getActivity(userTaskId)).thenReturn(activity);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("userTask1"))).thenReturn(userTaskId);
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");
        activityService.completeUserTask(userTaskId, List.of());

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.USER_TASK);
    }

    @Test
    public void test5() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test5.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("xor1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("xor2"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow4"))).thenReturn(UUID.randomUUID());
        when(scriptService.evaluateScript(eq("x = 1"), any())).thenReturn(Boolean.TRUE);

        activityService.execute(processInstanceId, token, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(4)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.EXCLUSIVE_GATEWAY);
    }

    @Test
    public void test6() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test6.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("xor1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("xor2"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow3"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow4"))).thenReturn(UUID.randomUUID());
        when(scriptService.evaluateScript(eq("x = 1"), any())).thenReturn(Boolean.FALSE);

        activityService.execute(processInstanceId, token, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(4)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.EXCLUSIVE_GATEWAY);
    }

    @Test
    public void test7() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test7.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID tokenId = UUID.randomUUID();

        Token token1 = new Token();
        token1.setId(tokenId);
        token1.setParentId(null);

        Token token2 = new Token();
        token2.setId(UUID.randomUUID());
        token2.setParentId(token1.getId());

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("startEvent")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("endEvent")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("parallel1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("parallel2")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow2")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow3")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow4")))).thenReturn(UUID.randomUUID());
        when(dbService.createToken(isNull())).thenReturn(token1);
        when(dbService.createToken(eq(token1.getId()))).thenReturn(token2);
        when(dbService.getToken(eq(token2.getId()))).thenReturn(token2);

        // join fires only once both incoming flows have arrived: first branch sees {flow2}, the
        // second sees {flow2, flow3}. Arrival recording itself is a no-op on the mock.
        when(dbService.getParallelGatewayArrivedFlows(eq(processInstanceId), eq("parallel2")))
            .thenReturn(Set.of("flow2"), Set.of("flow2", "flow3"));

        activityService.execute(processInstanceId, tokenId, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(4)).createActivity(eq(processInstanceId), any(UUID.class), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(4)).createActivity(eq(processInstanceId), any(UUID.class), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.PARALLEL_GATEWAY);

        // the join consumed its arrivals exactly once when it fired (so a later loop starts fresh)
        verify(dbService, times(1)).clearParallelGatewayArrivals(processInstanceId, "parallel2");
    }

    @Test
    public void parallelJoinDoesNotFireUntilAllBranchesArrive() {
        // A join with two incoming flows must stay parked while only one branch has arrived, and
        // must not consume (clear) the partial arrivals.
        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        BpmnElementModel join = new BpmnElementModel();
        join.setId("join1");
        join.setType(BpmnElementType.PARALLEL_GATEWAY);
        join.setIncoming(List.of("flowA", "flowB"));
        join.setOutgoing(List.of("flowOut"));

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(join);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.getParallelGatewayArrivedFlows(processInstanceId, "join1")).thenReturn(Set.of("flowA"));

        activityService.execute(processInstanceId, token, "join1");

        // not all incomings arrived: the join neither fires (no join activity) nor consumes arrivals
        verify(dbService, times(0)).createActivity(eq(processInstanceId), any(UUID.class), eq(join));
        verify(dbService, times(0)).clearParallelGatewayArrivals(any(), any());
    }

    @Test
    public void test8() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test8.bpmn"));
        String dummyBpmnStr = Files.readString(Path.of("src/test/files/test1.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);
        BpmnProcessDefinitionModel dummyBpmn = bpmnParseService.parse(dummyBpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID dummyProcessDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID dummyProcessInstanceId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID callActivityId = UUID.randomUUID();

        ProcessInstance dummyPi = new ProcessInstance();
        dummyPi.setId(dummyProcessInstanceId);
        dummyPi.setProcessDefinitionId(dummyProcessDefinitionId);
        dummyPi.setParentActivityId(callActivityId);

        ProcessDefinition dummyProcessDefinition = new ProcessDefinition();
        dummyProcessDefinition.setId(dummyProcessDefinitionId);

        UUID tokenId = UUID.randomUUID();

        Token token1 = new Token();
        token1.setId(tokenId);
        token1.setParentId(null);

        Token token2 = new Token();
        token2.setId(UUID.randomUUID());
        token2.setParentId(token1.getId());

        Activity callActivity = new Activity();
        callActivity.setId(callActivityId);
        callActivity.setToken(tokenId);
        callActivity.setProcessInstanceId(processInstanceId);
        callActivity.setBpmnElementId("callActivity1");
        callActivity.setType(BpmnElementType.CALL_ACTIVITY);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(bpmnService.getProcessDefinitionModelById(dummyProcessDefinitionId)).thenReturn(dummyBpmn);
        when(dbService.getMaxProcessDefinitionVersionByKey("dummy-process")).thenReturn(1);
        when(dbService.getProcessDefinition(eq("dummy-process"), any())).thenReturn(dummyProcessDefinition);
        when(dbService.createProcessInstance(any(UUID.class), eq(dummyProcessDefinitionId), any())).thenReturn(dummyProcessInstanceId);
        when(dbService.getProcessInstance(eq(dummyProcessInstanceId))).thenReturn(dummyPi);
        when(dbService.getProcessInstance(eq(processInstanceId))).thenReturn(pi);
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("startEvent1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("endEvent1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("callActivity1")))).thenReturn(callActivityId);
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow2")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(dummyProcessInstanceId), any(UUID.class), eq(dummyBpmn.getElement("startEvent")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(dummyProcessInstanceId), any(UUID.class), eq(dummyBpmn.getElement("endEvent")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(dummyProcessInstanceId), any(UUID.class), eq(dummyBpmn.getFlow("flow1")))).thenReturn(UUID.randomUUID());
        when(dbService.getActivity(callActivityId)).thenReturn(callActivity);
        when(dbService.createToken(isNull())).thenReturn(token1);
        when(dbService.createToken(eq(token1.getId()))).thenReturn(token2);
        when(dbService.getToken(eq(token1.getId()))).thenReturn(token1);

        activityService.execute(processInstanceId, tokenId, "startEvent1");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), any(UUID.class), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), any(UUID.class), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.CALL_ACTIVITY);
    }

    @Test
    public void messageThrowBroadcastsAndPassesThrough() throws IOException {
        // test-message-throw.bpmn: startEvent -> msgThrow (message "ping") -> endEvent.
        // The throw delivers the message in-engine (correlate) and continues to completion.
        String bpmnStr = Files.readString(Path.of("src/test/files/test-message-throw.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        assertThat(bpmn.getElement("msgThrow").getExtensions().getMessageEventExtension().getMessageName())
            .isEqualTo("ping");

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.getVariables(processInstanceId)).thenReturn(List.of());
        when(dbService.findMessageSubscriptions("ping", null)).thenReturn(List.of());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("msgThrow"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");

        verify(dbService).findMessageSubscriptions("ping", null);
        verify(dbService, times(1)).createActivity(processInstanceId, token, bpmn.getElement("endEvent"));
        verify(dbService, times(1)).completeProcessInstance(processInstanceId);
    }

    @Test
    public void messageCatchSubscribesAndResumesOnCorrelation() throws IOException {
        // test-message.bpmn: startEvent -> msgCatch (message "order-approved") -> endEvent.
        String bpmnStr = Files.readString(Path.of("src/test/files/test-message.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        // parser resolves messageRef -> message name
        assertThat(bpmn.getElement("msgCatch").getExtensions().getMessageEventExtension().getMessageName())
            .isEqualTo("order-approved");

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID msgActivityId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        Activity msgActivity = new Activity();
        msgActivity.setId(msgActivityId);
        msgActivity.setProcessInstanceId(processInstanceId);
        msgActivity.setBpmnElementId("msgCatch");
        msgActivity.setToken(token);
        msgActivity.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED);
        msgActivity.setType(BpmnElementType.MESSAGE_CATCH_EVENT);

        com.zorrodev.bpm.engine.dto.MessageSubscription subscription = new com.zorrodev.bpm.engine.dto.MessageSubscription();
        subscription.setId(subscriptionId);
        subscription.setProcessInstanceId(processInstanceId);
        subscription.setActivityId(msgActivityId);
        subscription.setMessageName("order-approved");

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.getActivity(msgActivityId)).thenReturn(msgActivity);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("msgCatch"))).thenReturn(msgActivityId);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());
        when(dbService.findMessageSubscriptions("order-approved", processInstanceId)).thenReturn(List.of(subscription));

        activityService.execute(processInstanceId, token, "startEvent");

        verify(dbService).createMessageSubscription(processInstanceId, msgActivityId, "order-approved", null, null);
        verify(dbService, times(0)).createActivity(processInstanceId, token, bpmn.getElement("endEvent"));

        activityService.correlateMessage("order-approved", processInstanceId, List.of());

        verify(dbService).consumeMessageSubscription(subscriptionId);
        verify(dbService, times(1)).createActivity(processInstanceId, token, bpmn.getElement("endEvent"));
        verify(dbService, times(1)).completeProcessInstance(processInstanceId);
    }

    @Test
    public void userTaskWithTimerBoundarySchedulesBoundaryJob() throws IOException {
        // test-boundary.bpmn: userTask1 has an interrupting timer boundary (PT10M) -> escalationEnd
        String bpmnStr = Files.readString(Path.of("src/test/files/test-boundary.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID userActivityId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("userTask1"))).thenReturn(userActivityId);
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");

        // token parks at the user task and a boundary timer is scheduled against it
        // (WO-PERF-3: now uses the 6-arg overload with processInstanceId for retention cleanup)
        verify(dbService).createTimerJob(eq(userActivityId), any(), eq("boundary1"), isNull(), isNull(), eq(processInstanceId));
        verify(dbService, times(0)).completeProcessInstance(any());
    }

    @Test
    public void boundaryTimerCancelsHostAndTakesBoundaryPath() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test-boundary.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID hostActivityId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        Activity host = new Activity();
        host.setId(hostActivityId);
        host.setProcessInstanceId(processInstanceId);
        host.setBpmnElementId("userTask1");
        host.setToken(token);
        host.setType(BpmnElementType.USER_TASK);
        host.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED);

        when(dbService.getActivity(hostActivityId)).thenReturn(host);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("escalationEnd"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flowB"))).thenReturn(UUID.randomUUID());
        // EventTrigger.fireBoundary now calls dbService.getToken (WO-ENG-1): linear path => pendingBranches = null
        Token hostTokenDto = new Token();
        hostTokenDto.setId(token);
        hostTokenDto.setPendingBranches(null);
        when(dbService.getToken(token)).thenReturn(hostTokenDto);

        activityService.fireBoundaryTimer(hostActivityId, "boundary1");

        verify(dbService).cancelActivity(hostActivityId);
        verify(dbService, times(1)).createActivity(processInstanceId, token, bpmn.getElement("escalationEnd"));
        verify(dbService, times(1)).completeProcessInstance(processInstanceId);
    }

    @Test
    public void nonInterruptingBoundaryTimerKeepsHostAndSpawnsParallelBranch() throws IOException {
        // test-boundary-noninterrupting.bpmn: userTask1 has a NON-interrupting timer boundary
        // (cancelActivity="false") -> boundaryTask. Firing must NOT cancel the host and must run
        // the boundary path on a new (parallel) token.
        String bpmnStr = Files.readString(Path.of("src/test/files/test-boundary-noninterrupting.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID hostActivityId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        Activity host = new Activity();
        host.setId(hostActivityId);
        host.setProcessInstanceId(processInstanceId);
        host.setBpmnElementId("userTask1");
        host.setToken(token);
        host.setType(BpmnElementType.USER_TASK);
        host.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED);

        Token branch = new Token();
        branch.setId(UUID.randomUUID());
        branch.setParentId(token);

        when(dbService.getActivity(hostActivityId)).thenReturn(host);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.createToken(token)).thenReturn(branch);
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("boundaryTask")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flowB")))).thenReturn(UUID.randomUUID());

        activityService.fireBoundaryTimer(hostActivityId, "boundary1");

        // host stays alive, a parallel branch token is created, and the boundary task is entered
        verify(dbService, times(0)).cancelActivity(hostActivityId);
        verify(dbService).createToken(token);
        verify(dbService, times(1)).createActivity(processInstanceId, branch.getId(), bpmn.getElement("boundaryTask"));
        verify(dbService, times(0)).completeProcessInstance(any());
    }

    @Test
    public void timerCatchEventSchedulesTimerJobAndParks() throws IOException {
        // test-timer.bpmn: startEvent -> timer1 (catch, PT5M) -> endEvent.
        // The token must park at the timer and a timer job be scheduled; end is not reached yet.
        String bpmnStr = Files.readString(Path.of("src/test/files/test-timer.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID timerActivityId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("timer1"))).thenReturn(timerActivityId);
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());

        Instant before = Instant.now();
        activityService.execute(processInstanceId, token, "startEvent");

        ArgumentCaptor<Instant> dueAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(dbService).createTimerJob(eq(timerActivityId), dueAtCaptor.capture(), isNull(), isNull(), isNull(), eq(processInstanceId));
        // PT5M from now
        assertThat(dueAtCaptor.getValue()).isBetween(before.plusSeconds(290), Instant.now().plusSeconds(310));
        verify(dbService, times(0)).createActivity(processInstanceId, token, bpmn.getElement("endEvent"));
        verify(dbService, times(0)).completeProcessInstance(any());
    }

    @Test
    public void intermediateThrowEventPassesThrough() throws IOException {
        // test-throw.bpmn: startEvent -> throw1 (intermediate throw) -> endEvent.
        // A plain throw event has no side effect and must flow straight through to completion.
        String bpmnStr = Files.readString(Path.of("src/test/files/test-throw.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("throw1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");

        verify(dbService, times(1)).createActivity(processInstanceId, token, bpmn.getElement("throw1"));
        verify(dbService, times(1)).createActivity(processInstanceId, token, bpmn.getElement("endEvent"));
        verify(dbService, times(1)).completeProcessInstance(processInstanceId);
    }

    @Test
    public void elementFailureRaisesIncidentInsteadOfPropagating() throws IOException {
        // test5.bpmn has an exclusive gateway whose condition "x = 1" is evaluated via the script
        // service. If the script returns a non-boolean, the (Boolean) cast in processFlow throws.
        // That failure must become an incident at the gateway, not propagate out of execute().
        String bpmnStr = Files.readString(Path.of("src/test/files/test5.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID gatewayActivityId = UUID.randomUUID();
        Activity gatewayActivity = new Activity();
        gatewayActivity.setId(gatewayActivityId);
        gatewayActivity.setBpmnElementId("xor1");
        gatewayActivity.setToken(token);
        gatewayActivity.setProcessInstanceId(processInstanceId);
        gatewayActivity.setType(BpmnElementType.EXCLUSIVE_GATEWAY);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.getActivitiesByTokenAndBpmnElementId(token, "xor1")).thenReturn(List.of(gatewayActivity));
        when(scriptService.evaluateScript(eq("x = 1"), any())).thenReturn("not-a-boolean");

        // must not throw: the bad condition result is turned into an incident
        activityService.execute(processInstanceId, token, "startEvent");

        verify(dbService).errorActivity(gatewayActivityId);
        verify(dbService).createIncident(eq(gatewayActivityId), any());
    }

    @Test
    public void catchEventParksTokenAndResumesOnSignal() throws IOException {
        // test-catch.bpmn: startEvent -> catch1 (intermediate catch) -> endEvent.
        // The catch event is a wait state: execute() must park the token there, and only
        // signal() may resume it to completion.
        String bpmnStr = Files.readString(Path.of("src/test/files/test-catch.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID catchActivityId = UUID.randomUUID();
        Activity catchActivity = new Activity();
        catchActivity.setType(BpmnElementType.INTERMEDIATE_CATCH_EVENT);
        catchActivity.setProcessInstanceId(processInstanceId);
        catchActivity.setBpmnElementId("catch1");
        catchActivity.setToken(token);
        catchActivity.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.getActivity(catchActivityId)).thenReturn(catchActivity);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("catch1"))).thenReturn(catchActivityId);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");

        // token parked at the catch event: end not reached, instance not completed
        verify(dbService, times(1)).createActivity(processInstanceId, token, bpmn.getElement("catch1"));
        verify(dbService, times(0)).createActivity(processInstanceId, token, bpmn.getElement("endEvent"));
        verify(dbService, times(0)).completeProcessInstance(any());

        activityService.signal(catchActivityId, List.of());

        // resumed: end reached and instance completed
        verify(dbService, times(1)).createActivity(processInstanceId, token, bpmn.getElement("endEvent"));
        verify(dbService, times(1)).completeProcessInstance(processInstanceId);
    }

    @Test
    public void completeServiceTaskIsIdempotentForFinishedActivity() {
        // RabbitMQ is at-least-once: a redelivered completion must not advance the token twice.
        UUID serviceTaskId = UUID.randomUUID();
        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setType(BpmnElementType.SERVICE_TASK);
        activity.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.COMPLETED);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);

        activityService.completeServiceTask(serviceTaskId, List.of());

        verify(dbService, times(0)).setVariables(any(), any());
        verify(dbService, times(0)).completeActivity(any());
        verify(dbService, times(0)).completeServiceTask(any());
    }

    @Test
    public void completionAcquiresInstanceLockBeforeAdvancing() {
        // The per-instance pessimistic lock is what serialises concurrent async branches so they
        // cannot race on a parallel-gateway join. It must be taken on every resume path, even the
        // idempotent short-circuit.
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setType(BpmnElementType.SERVICE_TASK);
        activity.setProcessInstanceId(processInstanceId);
        activity.setStatus(com.zorrodev.bpm.engine.entity.ActivityStatus.COMPLETED);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);

        activityService.completeServiceTask(serviceTaskId, List.of());

        verify(dbService).lockProcessInstance(processInstanceId);
    }

    @Test
    public void unhandledErrorEndRaisesIncident() {
        // An error end event with no catching boundary anywhere must be recorded as an incident,
        // not silently end the instance.
        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);
        pi.setParentActivityId(null); // top-level instance, nowhere to propagate

        BpmnElementModel errEnd = new BpmnElementModel();
        errEnd.setId("errEnd");
        errEnd.setType(BpmnElementType.ERROR_END_EVENT);
        com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel ext =
            new com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel();
        com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel ed =
            new com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel();
        ed.setType(com.zorrodev.bpm.engine.bpmn.model.EventDefinitionType.ERROR);
        ed.setCode("E-1");
        ext.setEventDefinition(ed);
        errEnd.setExtensions(ext);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(errEnd);

        Token token = new Token();
        token.setId(tokenId);

        UUID activityId = UUID.randomUUID();
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.getToken(tokenId)).thenReturn(token);
        when(dbService.createActivity(processInstanceId, tokenId, errEnd)).thenReturn(activityId);

        activityService.execute(processInstanceId, tokenId, "errEnd");

        verify(dbService).errorActivity(activityId);
        verify(dbService).createIncident(eq(activityId), any());
    }

    @Test
    public void unsupportedElementTypeRaisesIncidentInsteadOfLosingToken() {
        // An element type with no registered handler must not silently drop the token (which would
        // strand the instance). It is parked as an incident instead.
        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        BpmnElementModel unsupported = new BpmnElementModel();
        unsupported.setId("unsupported1");
        unsupported.setType(BpmnElementType.EVENT); // no handler registered

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(unsupported);

        UUID activityId = UUID.randomUUID();
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, unsupported)).thenReturn(activityId);

        activityService.execute(processInstanceId, token, "unsupported1");

        verify(dbService).errorActivity(activityId);
        verify(dbService).createIncident(eq(activityId), any());
    }

    @Test
    public void recursionDepthGuardStopsInfiniteLoop() throws IOException {
        // test-loop.bpmn: startEvent -> parallel1, and flow2: parallel1 -> parallel1 (self loop).
        // Without the depth guard this recurses through execute() until StackOverflowError.
        String bpmnStr = Files.readString(Path.of("src/test/files/test-loop.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        Token token = new Token();
        token.setId(UUID.randomUUID());
        token.setParentId(null);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createToken(any())).thenReturn(token);

        // Use a real ExecutionContext with low depth limit (not a mock)
        ExecutionContext realCtx = new ExecutionContext();
        ReflectionTestUtils.setField(realCtx, "maxExecutionDepth", 100);
        ReflectionTestUtils.setField(activityService, "executionContext", realCtx);

        assertThatThrownBy(() -> activityService.execute(processInstanceId, tokenId, "parallel1"))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("Execution depth limit");
    }

    @Test
    public void startScriptBusinessRuleHandlersRegistered() {
        // WO-A-08: verify HandlerRegistry wires START_EVENT, SCRIPT_TASK, BUSINESS_RULE_TASK
        assertThat(handlerRegistry.get(BpmnElementType.START_EVENT))
            .isInstanceOf(com.zorrodev.bpm.engine.handler.StartThrowEventHandler.StartEvent.class);
        assertThat(handlerRegistry.get(BpmnElementType.SCRIPT_TASK))
            .isInstanceOf(com.zorrodev.bpm.engine.handler.SyncTaskHandler.ScriptTask.class);
        assertThat(handlerRegistry.get(BpmnElementType.BUSINESS_RULE_TASK))
            .isInstanceOf(com.zorrodev.bpm.engine.handler.SyncTaskHandler.BusinessRuleTask.class);
    }

    @Test
    public void endEscalationHandlersRegistered() {
        // WO-A-08: verify HandlerRegistry wires 5 end/escalation handler types
        assertThat(handlerRegistry.get(BpmnElementType.END_EVENT))
            .isInstanceOf(com.zorrodev.bpm.engine.handler.EndEventHandler.EndEvent.class);
        assertThat(handlerRegistry.get(BpmnElementType.TERMINATE_END_EVENT))
            .isInstanceOf(com.zorrodev.bpm.engine.handler.EndEventHandler.TerminateEndEvent.class);
        assertThat(handlerRegistry.get(BpmnElementType.ERROR_END_EVENT))
            .isInstanceOf(com.zorrodev.bpm.engine.handler.EndEventHandler.ErrorEndEvent.class);
        assertThat(handlerRegistry.get(BpmnElementType.ESCALATION_END_EVENT))
            .isInstanceOf(com.zorrodev.bpm.engine.handler.EndEventHandler.EscalationEndEvent.class);
        assertThat(handlerRegistry.get(BpmnElementType.ESCALATION_THROW_EVENT))
            .isInstanceOf(com.zorrodev.bpm.engine.handler.StartThrowEventHandler.EscalationThrowEvent.class);
    }
}
