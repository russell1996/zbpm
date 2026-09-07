package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionType;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnParseServiceImplTest {

    @Test
    void testNoStartEvents() throws IOException {
        String bpmnFile = "src/test/files/process0-1.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();

        assertThatThrownBy(() -> {
            service.parse(bpmnStr);
        }).isInstanceOf(BpmnParseException.class);
    }

    @Test
    void testNoEndEvents() throws IOException {
        String bpmnFile = "src/test/files/process0-2.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();

        assertThatThrownBy(() -> {
            service.parse(bpmnStr);
        }).isInstanceOf(BpmnParseException.class);
    }

    @Test
    void testManyStartEvents() throws IOException {
        String bpmnFile = "src/test/files/process0-3.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();

        assertThatThrownBy(() -> {
            service.parse(bpmnStr);
        }).isInstanceOf(BpmnParseException.class);
    }

    @Test
    void testProcess1() throws IOException {
        // start event -> end event
        String bpmnFile = "src/test/files/process1.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getExecutionPlatformVersion()).isEqualTo("8.6.0");
        assertThat(bpmn.getName()).isEqualTo("Process 1");
        assertThat(bpmn.getKey()).isEqualTo("process1");
        assertThat(bpmn.getElements()).isNotNull().hasSize(2);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(1);

        BpmnElementModel startEvent = bpmn.getElement("startEvent");
        assertThat(startEvent.getId()).isEqualTo("startEvent");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent");
        assertThat(endEvent.getId()).isEqualTo("endEvent");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow1");
    }

    @Test
    void testProcess2() throws IOException {
        // start event -> service task -> end event
        String bpmnFile = "src/test/files/process2.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getName()).isEqualTo("Process 2");
        assertThat(bpmn.getKey()).isEqualTo("process2");
        assertThat(bpmn.getElements()).isNotNull().hasSize(3);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(2);

        BpmnElementModel startEvent = bpmn.getElement("startEvent");
        assertThat(startEvent.getId()).isEqualTo("startEvent");
        assertThat(startEvent.getName()).isEqualTo("Start Event");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent");
        assertThat(endEvent.getId()).isEqualTo("endEvent");
        assertThat(endEvent.getName()).isEqualTo("End Event");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow2");

        BpmnElementModel serviceTask = bpmn.getElement("serviceTask");
        assertThat(serviceTask.getId()).isEqualTo("serviceTask");
        assertThat(serviceTask.getName()).isEqualTo("Service Task");
        assertThat(serviceTask.getType()).isEqualTo(BpmnElementType.SERVICE_TASK);
        assertThat(serviceTask.getOutgoing()).isNotNull().hasSize(1);
        assertThat(serviceTask.getIncoming()).isNotNull().hasSize(1);
        assertThat(serviceTask.getIncoming().get(0)).isEqualTo("flow1");
        assertThat(serviceTask.getOutgoing().get(0)).isEqualTo("flow2");
        ServiceTaskExtensionModel extension = serviceTask.getExtensions().getServiceTaskExtension();
        assertThat(extension).isNotNull();
        assertThat(extension.getJob()).isEqualTo("job1");
    }

    @Test
    void testProcess3() throws IOException {
        // start event -> user task -> end event
        String bpmnFile = "src/test/files/process3.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getName()).isEqualTo("Process 3");
        assertThat(bpmn.getKey()).isEqualTo("process3");
        assertThat(bpmn.getElements()).isNotNull().hasSize(3);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(2);

        BpmnElementModel startEvent = bpmn.getElement("startEvent");
        assertThat(startEvent.getId()).isEqualTo("startEvent");
        assertThat(startEvent.getName()).isEqualTo("Start Event");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent");
        assertThat(endEvent.getId()).isEqualTo("endEvent");
        assertThat(endEvent.getName()).isEqualTo("End Event");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow2");

        BpmnElementModel userTask = bpmn.getElement("userTask");
        assertThat(userTask.getId()).isEqualTo("userTask");
        assertThat(userTask.getName()).isEqualTo("User Task");
        assertThat(userTask.getType()).isEqualTo(BpmnElementType.USER_TASK);
        assertThat(userTask.getOutgoing()).isNotNull().hasSize(1);
        assertThat(userTask.getIncoming()).isNotNull().hasSize(1);
        assertThat(userTask.getIncoming().get(0)).isEqualTo("flow1");
        assertThat(userTask.getOutgoing().get(0)).isEqualTo("flow2");
        UserTaskExtensionModel extension = userTask.getExtensions().getUserTaskExtension();
        assertThat(extension).isNotNull();
        assertThat(extension.getAssignee()).isEqualTo("assignee1");
        assertThat(extension.getCandidateUsers()).isEqualTo("candidateUser1,candidateUser2");
        assertThat(extension.getCandidateGroups()).isEqualTo("candidateGroup1,candidateGroup2");
        assertThat(extension.getFormKey()).isEqualTo("formKey1");
    }

    @Test
    void testProcess4() throws IOException {
        //                     -> user task 1
        // start event -> xor1                -> xor2 -> end event
        //                     -> user task 2
        String bpmnFile = "src/test/files/process4.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getName()).isEqualTo("Process 4");
        assertThat(bpmn.getKey()).isEqualTo("process4");
        assertThat(bpmn.getElements()).isNotNull().hasSize(6);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(6);

        BpmnElementModel startEvent = bpmn.getElement("startEvent");
        assertThat(startEvent.getId()).isEqualTo("startEvent");
        assertThat(startEvent.getName()).isEqualTo("Start Event");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent");
        assertThat(endEvent.getId()).isEqualTo("endEvent");
        assertThat(endEvent.getName()).isEqualTo("End Event");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow6");

        BpmnElementModel userTask1 = bpmn.getElement("userTask1");
        assertThat(userTask1.getId()).isEqualTo("userTask1");
        assertThat(userTask1.getName()).isEqualTo("User Task 1");
        assertThat(userTask1.getType()).isEqualTo(BpmnElementType.USER_TASK);
        assertThat(userTask1.getOutgoing()).isNotNull().hasSize(1);
        assertThat(userTask1.getIncoming()).isNotNull().hasSize(1);
        assertThat(userTask1.getIncoming().get(0)).isEqualTo("flow2");
        assertThat(userTask1.getOutgoing().get(0)).isEqualTo("flow4");

        BpmnElementModel userTask2 = bpmn.getElement("userTask2");
        assertThat(userTask2.getId()).isEqualTo("userTask2");
        assertThat(userTask2.getName()).isEqualTo("User Task 2");
        assertThat(userTask2.getType()).isEqualTo(BpmnElementType.USER_TASK);
        assertThat(userTask2.getOutgoing()).isNotNull().hasSize(1);
        assertThat(userTask2.getIncoming()).isNotNull().hasSize(1);
        assertThat(userTask2.getIncoming().get(0)).isEqualTo("flow3");
        assertThat(userTask2.getOutgoing().get(0)).isEqualTo("flow5");

        BpmnElementModel xor1 = bpmn.getElement("xor1");
        assertThat(xor1.getId()).isEqualTo("xor1");
        assertThat(xor1.getName()).isEqualTo("XOR 1");
        assertThat(xor1.getType()).isEqualTo(BpmnElementType.EXCLUSIVE_GATEWAY);
        assertThat(xor1.getIncoming()).isNotNull().hasSize(1);
        assertThat(xor1.getOutgoing()).isNotNull().hasSize(2);
        assertThat(xor1.getIncoming().get(0)).isEqualTo("flow1");
        assertThat(xor1.getOutgoing()).contains("flow2", "flow3");
        assertThat(xor1.getExtensions()).isNotNull();
        assertThat(xor1.getExtensions().getExclusiveGatewayExtension()).isNotNull();
        assertThat(xor1.getExtensions().getExclusiveGatewayExtension().getDefaultFlowId()).isEqualTo("flow2");

        BpmnElementModel xor2 = bpmn.getElement("xor2");
        assertThat(xor2.getId()).isEqualTo("xor2");
        assertThat(xor2.getName()).isEqualTo("XOR 2");
        assertThat(xor2.getType()).isEqualTo(BpmnElementType.EXCLUSIVE_GATEWAY);
        assertThat(xor2.getIncoming()).isNotNull().hasSize(2);
        assertThat(xor2.getOutgoing()).isNotNull().hasSize(1);
        assertThat(xor2.getIncoming()).contains("flow4", "flow5");
        assertThat(xor2.getOutgoing().get(0)).isEqualTo("flow6");

        BpmnFlowModel flow1 = bpmn.getFlow("flow1");
        assertThat(flow1.getFlowId()).isEqualTo("flow1");
        assertThat(flow1.getSourceRef()).isEqualTo("startEvent");
        assertThat(flow1.getTargetRef()).isEqualTo("xor1");

        BpmnFlowModel flow2 = bpmn.getFlow("flow2");
        assertThat(flow2.getFlowId()).isEqualTo("flow2");
        assertThat(flow2.getSourceRef()).isEqualTo("xor1");
        assertThat(flow2.getTargetRef()).isEqualTo("userTask1");

        BpmnFlowModel flow3 = bpmn.getFlow("flow3");
        assertThat(flow3.getFlowId()).isEqualTo("flow3");
        assertThat(flow3.getSourceRef()).isEqualTo("xor1");
        assertThat(flow3.getTargetRef()).isEqualTo("userTask2");
        assertThat(flow3.getConditionExpression()).isNotNull();
        assertThat(flow3.getConditionExpression().getExpression()).isEqualTo("=a > 5");
        assertThat(flow3.getConditionExpression().getType()).isEqualTo("bpmn:tFormalExpression");

        BpmnFlowModel flow4 = bpmn.getFlow("flow4");
        assertThat(flow4.getFlowId()).isEqualTo("flow4");
        assertThat(flow4.getSourceRef()).isEqualTo("userTask1");
        assertThat(flow4.getTargetRef()).isEqualTo("xor2");

        BpmnFlowModel flow5 = bpmn.getFlow("flow5");
        assertThat(flow5.getFlowId()).isEqualTo("flow5");
        assertThat(flow5.getSourceRef()).isEqualTo("userTask2");
        assertThat(flow5.getTargetRef()).isEqualTo("xor2");

        BpmnFlowModel flow6 = bpmn.getFlow("flow6");
        assertThat(flow6.getFlowId()).isEqualTo("flow6");
        assertThat(flow6.getSourceRef()).isEqualTo("xor2");
        assertThat(flow6.getTargetRef()).isEqualTo("endEvent");
    }

    @Test
    void testParse8() throws IOException {
        String bpmnFile = "src/test/files/test8.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getName()).isEqualTo("Test8 Process");
        assertThat(bpmn.getKey()).isEqualTo("call-activity-process");
        assertThat(bpmn.getElements()).isNotNull().hasSize(3);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(2);

        BpmnElementModel startEvent = bpmn.getElement("startEvent1");
        assertThat(startEvent.getId()).isEqualTo("startEvent1");
        assertThat(startEvent.getName()).isEqualTo("Start Event 1");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent1");
        assertThat(endEvent.getId()).isEqualTo("endEvent1");
        assertThat(endEvent.getName()).isEqualTo("End Event 1");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow2");

        BpmnElementModel callActivity = bpmn.getElement("callActivity1");
        assertThat(callActivity.getId()).isEqualTo("callActivity1");
        assertThat(callActivity.getName()).isEqualTo("Call Activity 1");
        assertThat(callActivity.getType()).isEqualTo(BpmnElementType.CALL_ACTIVITY);
        assertThat(callActivity.getOutgoing()).isNotNull().hasSize(1);
        assertThat(callActivity.getIncoming()).isNotNull().hasSize(1);
        assertThat(callActivity.getIncoming().get(0)).isEqualTo("flow1");
        assertThat(callActivity.getOutgoing().get(0)).isEqualTo("flow2");
        assertThat(callActivity.getExtensions()).isNotNull();
        assertThat(callActivity.getExtensions().getCallActivityExtension()).isNotNull();
        assertThat(callActivity.getExtensions().getCallActivityExtension().getProcessId()).isEqualTo("dummy-process");
        assertThat(callActivity.getExtensions().getCallActivityExtension().getBindingType()).isEqualTo("deployment");
        assertThat(callActivity.getExtensions().getCallActivityExtension().getPropagateAllChildVariables()).isFalse();

        BpmnFlowModel flow5 = bpmn.getFlow("flow1");
        assertThat(flow5.getFlowId()).isEqualTo("flow1");
        assertThat(flow5.getSourceRef()).isEqualTo("startEvent1");
        assertThat(flow5.getTargetRef()).isEqualTo("callActivity1");

        BpmnFlowModel flow6 = bpmn.getFlow("flow2");
        assertThat(flow6.getFlowId()).isEqualTo("flow2");
        assertThat(flow6.getSourceRef()).isEqualTo("callActivity1");
        assertThat(flow6.getTargetRef()).isEqualTo("endEvent1");
    }

    @Test
    void testParseMessageStartOnly() throws IOException {
        // a process whose only start is a message start must parse (no plain start required)
        String bpmnStr = Files.readString(Path.of("src/test/files/test-message-start.bpmn"));
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl().parse(bpmnStr);

        assertThat(bpmn.getStartEvent()).isNull(); // no plain start
        assertThat(bpmn.getMessageStartEvents()).hasSize(1);
        BpmnElementModel msgStart = bpmn.getMessageStartEvents().get(0);
        assertThat(msgStart.getId()).isEqualTo("msgStart");
        assertThat(msgStart.getType()).isEqualTo(BpmnElementType.MESSAGE_START_EVENT);
        assertThat(msgStart.getExtensions().getMessageEventExtension().getMessageName()).isEqualTo("order-received");
    }

    @Test
    void testParseErrorBoundary() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test-error-boundary.bpmn"));
        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        BpmnElementModel boundary = bpmn.getElement("errBoundary");
        assertThat(boundary).isNotNull();
        assertThat(boundary.getType()).isEqualTo(BpmnElementType.ERROR_BOUNDARY_EVENT);
        assertThat(boundary.getExtensions().getBoundaryEventExtension().getAttachedToRef()).isEqualTo("sub1");
        assertThat(boundary.getExtensions().getEventDefinition().getCode()).isEqualTo("E-1");
        assertThat(boundary.getOutgoing()).containsExactly("flowB");

        BpmnElementModel subErrEnd = bpmn.getElement("subErrEnd");
        assertThat(subErrEnd.getType()).isEqualTo(BpmnElementType.ERROR_END_EVENT);
        assertThat(subErrEnd.getExtensions().getEventDefinition().getCode()).isEqualTo("E-1");
    }

    @Test
    void testParseEventDefinitions() throws IOException {
        // error/signal/escalation event definitions are resolved against definitions-level declarations
        String bpmnStr = Files.readString(Path.of("src/test/files/test-event-definitions.bpmn"));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        var errEnd = bpmn.getElement("errEnd").getExtensions().getEventDefinition();
        assertThat(errEnd).isNotNull();
        assertThat(errEnd.getType()).isEqualTo(EventDefinitionType.ERROR);
        assertThat(errEnd.getReference()).isEqualTo("err1");
        assertThat(errEnd.getCode()).isEqualTo("E-500");

        var sigCatch = bpmn.getElement("sigCatch").getExtensions().getEventDefinition();
        assertThat(sigCatch).isNotNull();
        assertThat(sigCatch.getType()).isEqualTo(EventDefinitionType.SIGNAL);
        assertThat(sigCatch.getName()).isEqualTo("my-signal");

        var escThrow = bpmn.getElement("escThrow").getExtensions().getEventDefinition();
        assertThat(escThrow).isNotNull();
        assertThat(escThrow.getType()).isEqualTo(EventDefinitionType.ESCALATION);
        assertThat(escThrow.getCode()).isEqualTo("ESC-1");
    }

    @Test
    void testParseSendReceiveTasks() throws IOException {
        // send/receive tasks resolve their messageRef to the definitions-level message name
        String bpmnStr = Files.readString(Path.of("src/test/files/test-send-receive.bpmn"));
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl().parse(bpmnStr);

        BpmnElementModel send = bpmn.getElement("sendTask1");
        assertThat(send.getType()).isEqualTo(BpmnElementType.SEND_TASK);
        assertThat(send.getExtensions().getMessageEventExtension().getMessageName()).isEqualTo("notify");
        assertThat(send.getOutgoing()).containsExactly("flow2");

        BpmnElementModel receive = bpmn.getElement("receiveTask1");
        assertThat(receive.getType()).isEqualTo(BpmnElementType.RECEIVE_TASK);
        assertThat(receive.getExtensions().getMessageEventExtension().getMessageName()).isEqualTo("approve");
        assertThat(receive.getOutgoing()).containsExactly("flow3");
    }

    @Test
    void testParseSignalCatchAndThrow() throws IOException {
        // intermediate catch/throw with a signalEventDefinition become SIGNAL_CATCH/THROW with the
        // signal name resolved from the definitions-level <signal>
        String bpmnStr = Files.readString(Path.of("src/test/files/test-signal.bpmn"));
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl().parse(bpmnStr);

        BpmnElementModel catchA = bpmn.getElement("signalCatchA");
        assertThat(catchA.getType()).isEqualTo(BpmnElementType.SIGNAL_CATCH_EVENT);
        assertThat(catchA.getExtensions().getEventDefinition().getType()).isEqualTo(EventDefinitionType.SIGNAL);
        assertThat(catchA.getExtensions().getEventDefinition().getName()).isEqualTo("go");

        BpmnElementModel throwEvt = bpmn.getElement("signalThrow");
        assertThat(throwEvt.getType()).isEqualTo(BpmnElementType.SIGNAL_THROW_EVENT);
        assertThat(throwEvt.getExtensions().getEventDefinition().getName()).isEqualTo("go");
    }

    @Test
    void testParseSignalStartEvent() throws IOException {
        // a start event with a signalEventDefinition becomes SIGNAL_START_EVENT (no plain start needed)
        String bpmnStr = Files.readString(Path.of("src/test/files/test-signal-start-receiver.bpmn"));
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl().parse(bpmnStr);

        assertThat(bpmn.getStartEvent()).isNull();
        assertThat(bpmn.getSignalStartEvents()).hasSize(1);
        BpmnElementModel sigStart = bpmn.getSignalStartEvents().get(0);
        assertThat(sigStart.getId()).isEqualTo("signalStart");
        assertThat(sigStart.getType()).isEqualTo(BpmnElementType.SIGNAL_START_EVENT);
        assertThat(sigStart.getExtensions().getEventDefinition().getName()).isEqualTo("kickoff");
    }

    @Test
    void testParseLinkEvents() throws IOException {
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-link-events.bpmn")));

        BpmnElementModel linkThrow = bpmn.getElement("linkThrow");
        assertThat(linkThrow.getType()).isEqualTo(BpmnElementType.LINK_THROW_EVENT);
        assertThat(linkThrow.getExtensions().getEventDefinition().getName()).isEqualTo("L1");

        BpmnElementModel linkCatch = bpmn.getElement("linkCatch");
        assertThat(linkCatch.getType()).isEqualTo(BpmnElementType.LINK_CATCH_EVENT);
        assertThat(linkCatch.getExtensions().getEventDefinition().getName()).isEqualTo("L1");
    }

    @Test
    void testParseScriptTask() throws IOException {
        // a scriptTask carries its inline FEEL script and result variable into the script-task extension
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-script-task.bpmn")));

        BpmnElementModel scriptSum = bpmn.getElement("scriptSum");
        assertThat(scriptSum.getType()).isEqualTo(BpmnElementType.SCRIPT_TASK);
        assertThat(scriptSum.getExtensions().getScriptTaskExtension().getScript()).isEqualTo("a + b");
        assertThat(scriptSum.getExtensions().getScriptTaskExtension().getResultVariable()).isEqualTo("sum");
        assertThat(scriptSum.getExtensions().getScriptTaskExtension().getScriptFormat()).isEqualTo("feel");
        assertThat(scriptSum.getOutgoing()).containsExactly("flow2");
    }

    @Test
    void testParseScriptTaskZeebeScript() throws IOException {
        // Camunda 8 style: <zeebe:script expression="=a + b" resultVariable="sum"> — the leading '=' is stripped
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-script-task-zeebe.bpmn")));

        BpmnElementModel scriptSum = bpmn.getElement("scriptSum");
        assertThat(scriptSum.getType()).isEqualTo(BpmnElementType.SCRIPT_TASK);
        assertThat(scriptSum.getExtensions().getScriptTaskExtension().getScript()).isEqualTo("a + b");
        assertThat(scriptSum.getExtensions().getScriptTaskExtension().getResultVariable()).isEqualTo("sum");
    }

    @Test
    void testParseEventSubProcess() throws IOException {
        // a <subProcess triggeredByEvent="true"> with a message start becomes an EVENT_SUB_PROCESS; its
        // start event is marked (so it is not a process-level start) and the trigger message is resolved
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-event-subprocess.bpmn")));

        BpmnElementModel evSub = bpmn.getElement("cancelHandler");
        assertThat(evSub.getType()).isEqualTo(BpmnElementType.EVENT_SUB_PROCESS);
        assertThat(evSub.getExtensions().getSubProcessExtension().isEventSubProcess()).isTrue();
        assertThat(evSub.getExtensions().getSubProcessExtension().isInterrupting()).isTrue();
        assertThat(evSub.getExtensions().getSubProcessExtension().getTriggerMessageName()).isEqualTo("cancelOrder");
        assertThat(evSub.getExtensions().getSubProcessExtension().getStartEventId()).isEqualTo("evStart");

        // the event-subprocess start must not be collected as a process-level message start
        assertThat(bpmn.getMessageStartEvents()).noneMatch(e -> e.getId().equals("evStart"));
        assertThat(bpmn.getElement("evStart").getEventSubProcessId()).isEqualTo("cancelHandler");

        // isInterrupting="false" is parsed as a non-interrupting event sub-process
        BpmnProcessDefinitionModel ni = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-event-subprocess-noninterrupting.bpmn")));
        assertThat(ni.getElement("pingHandler").getExtensions().getSubProcessExtension().isInterrupting()).isFalse();
    }

    @Test
    void testParseBusinessRuleTask() throws IOException {
        // DMN variant: zeebe:calledDecision -> BUSINESS_RULE_TASK with decisionId + resultVariable
        BpmnProcessDefinitionModel dmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-business-rule-dmn.bpmn")));
        BpmnElementModel decideDmn = dmn.getElement("decide");
        assertThat(decideDmn.getType()).isEqualTo(BpmnElementType.BUSINESS_RULE_TASK);
        assertThat(decideDmn.getExtensions().getBusinessRuleExtension().getDecisionId()).isEqualTo("discount");
        assertThat(decideDmn.getExtensions().getBusinessRuleExtension().getResultVariable()).isEqualTo("discount");

        // FEEL variant: zeebe:script -> expression + resultVariable
        BpmnProcessDefinitionModel feel = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-business-rule-feel.bpmn")));
        var brFeel = feel.getElement("decide").getExtensions().getBusinessRuleExtension();
        assertThat(brFeel.getExpression()).isEqualTo("=amount * 2");
        assertThat(brFeel.getResultVariable()).isEqualTo("doubled");
    }

    @Test
    void testParseMultiInstance() throws IOException {
        // a userTask's multiInstanceLoopCharacteristics is read into the multi-instance extension
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-multi-instance.bpmn")));

        BpmnElementModel miTask = bpmn.getElement("miTask");
        assertThat(miTask.getType()).isEqualTo(BpmnElementType.USER_TASK);
        assertThat(miTask.getExtensions().getMultiInstanceExtension().isSequential()).isFalse();
        assertThat(miTask.getExtensions().getMultiInstanceExtension().getCardinality()).isEqualTo("3");

        // Camunda 8 variant: zeebe:loopCharacteristics inputCollection (leading '=' stripped)
        BpmnProcessDefinitionModel zeebe = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-multi-instance-zeebe.bpmn")));
        var miZeebe = zeebe.getElement("miTask").getExtensions().getMultiInstanceExtension();
        assertThat(miZeebe.getInputCollection()).isEqualTo("[1, 2, 3]");
        assertThat(miZeebe.getInputElement()).isEqualTo("item");
        assertThat(miZeebe.getCardinality()).isNull();

        // sequential variant with a completion condition
        BpmnProcessDefinitionModel seq = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-multi-instance-completion.bpmn")));
        var miSeq = seq.getElement("miTask").getExtensions().getMultiInstanceExtension();
        assertThat(miSeq.isSequential()).isTrue();
        assertThat(miSeq.getCardinality()).isEqualTo("5");
        assertThat(miSeq.getCompletionCondition()).isEqualTo("stop = true");
    }

    @Test
    void testParseTransactionCancel() throws IOException {
        // a <transaction> is flattened like a subprocess; a cancel end -> CANCEL_END_EVENT and a cancel
        // boundary on the transaction -> CANCEL_BOUNDARY_EVENT
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-transaction-cancel.bpmn")));

        BpmnElementModel tx = bpmn.getElement("tx");
        assertThat(tx.getType()).isEqualTo(BpmnElementType.SUB_PROCESS);
        assertThat(tx.getExtensions().getSubProcessExtension().getStartEventId()).isEqualTo("txStart");

        assertThat(bpmn.getElement("cancelEnd").getType()).isEqualTo(BpmnElementType.CANCEL_END_EVENT);

        BpmnElementModel cb = bpmn.getElement("cb");
        assertThat(cb.getType()).isEqualTo(BpmnElementType.CANCEL_BOUNDARY_EVENT);
        assertThat(cb.getExtensions().getBoundaryEventExtension().getAttachedToRef()).isEqualTo("tx");
    }

    @Test
    void testParseCompensation() throws IOException {
        // compensation boundary -> COMPENSATION_BOUNDARY_EVENT with its handler resolved from <association>;
        // an intermediate throw with compensateEventDefinition -> COMPENSATION_THROW_EVENT
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-compensation.bpmn")));

        BpmnElementModel bA = bpmn.getElement("bA");
        assertThat(bA.getType()).isEqualTo(BpmnElementType.COMPENSATION_BOUNDARY_EVENT);
        assertThat(bA.getExtensions().getBoundaryEventExtension().getAttachedToRef()).isEqualTo("taskA");
        assertThat(bA.getExtensions().getBoundaryEventExtension().getCompensationHandlerId()).isEqualTo("handlerA");

        assertThat(bpmn.getElement("compThrow").getType()).isEqualTo(BpmnElementType.COMPENSATION_THROW_EVENT);
    }

    @Test
    void testParseIoMapping() throws IOException {
        // a task's <zeebe:ioMapping> input/output mappings are read into the io-mapping extension
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-io-mapping.bpmn")));

        BpmnElementModel review = bpmn.getElement("review");
        var io = review.getExtensions().getIoMappingExtension();
        assertThat(io.getInputs()).hasSize(1);
        assertThat(io.getInputs().get(0).getSource()).isEqualTo("=orderId");
        assertThat(io.getInputs().get(0).getTarget()).isEqualTo("taskOrder");
        assertThat(io.getOutputs()).hasSize(1);
        assertThat(io.getOutputs().get(0).getSource()).isEqualTo("=approved");
        assertThat(io.getOutputs().get(0).getTarget()).isEqualTo("decision");
    }

    @Test
    void testParseMessageCorrelationKey() throws IOException {
        // a message's <zeebe:subscription correlationKey="..."> is read into the subscriber's message extension
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-message-correlation-key.bpmn")));

        BpmnElementModel awaitUpdate = bpmn.getElement("awaitUpdate");
        assertThat(awaitUpdate.getType()).isEqualTo(BpmnElementType.MESSAGE_CATCH_EVENT);
        assertThat(awaitUpdate.getExtensions().getMessageEventExtension().getMessageName()).isEqualTo("orderUpdate");
        assertThat(awaitUpdate.getExtensions().getMessageEventExtension().getCorrelationKeyExpression()).isEqualTo("=orderId");
    }

    @Test
    void testParseConditionalEvents() throws IOException {
        // an intermediate catch with a conditionalEventDefinition becomes CONDITIONAL_CATCH_EVENT and a
        // boundary with one becomes CONDITIONAL_BOUNDARY_EVENT; both carry the FEEL condition expression
        BpmnProcessDefinitionModel catchBpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-conditional-catch.bpmn")));
        BpmnElementModel condCatch = catchBpmn.getElement("condCatch");
        assertThat(condCatch.getType()).isEqualTo(BpmnElementType.CONDITIONAL_CATCH_EVENT);
        assertThat(condCatch.getExtensions().getEventDefinition().getType()).isEqualTo(EventDefinitionType.CONDITIONAL);
        assertThat(condCatch.getExtensions().getEventDefinition().getExpression()).isEqualTo("approved = true");

        BpmnProcessDefinitionModel boundaryBpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-conditional-boundary.bpmn")));
        BpmnElementModel boundary = boundaryBpmn.getElement("abortBoundary");
        assertThat(boundary.getType()).isEqualTo(BpmnElementType.CONDITIONAL_BOUNDARY_EVENT);
        assertThat(boundary.getExtensions().getEventDefinition().getExpression()).isEqualTo("abort = true");
        assertThat(boundary.getExtensions().getBoundaryEventExtension().getAttachedToRef()).isEqualTo("work");
    }

    @Test
    void testParseConditionalFilter() throws IOException {
        // WO-C8-29, критерий 2: zeebe:conditionalFilter внутри conditionalEventDefinition
        // резолвится в eventDefinition-расширение (имена + события); без фильтра — null,
        // поведение прежнее.
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-conditional-filter.bpmn")));
        BpmnElementModel condCatch = bpmn.getElement("condCatch");
        assertThat(condCatch.getType()).isEqualTo(BpmnElementType.CONDITIONAL_CATCH_EVENT);
        assertThat(condCatch.getExtensions().getEventDefinition().getConditionalFilter()).isNotNull();
        assertThat(condCatch.getExtensions().getEventDefinition().getConditionalFilter().variableNames())
            .containsExactly("approved");
        assertThat(condCatch.getExtensions().getEventDefinition().getConditionalFilter().variableEvents())
            .containsExactly("create", "update");

        BpmnProcessDefinitionModel legacyBpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-conditional-catch.bpmn")));
        assertThat(legacyBpmn.getElement("condCatch").getExtensions().getEventDefinition()
            .getConditionalFilter()).isNull();
    }

    @Test
    void testParseInclusiveGateway() throws IOException {
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-inclusive-gateway.bpmn")));

        BpmnElementModel split = bpmn.getElement("split");
        assertThat(split.getType()).isEqualTo(BpmnElementType.INCLUSIVE_GATEWAY);
        assertThat(split.getExtensions().getExclusiveGatewayExtension().getDefaultFlowId()).isEqualTo("flowDefault");
        assertThat(split.getOutgoing()).containsExactlyInAnyOrder("flowA", "flowB", "flowDefault");

        BpmnElementModel join = bpmn.getElement("join");
        assertThat(join.getType()).isEqualTo(BpmnElementType.INCLUSIVE_GATEWAY);
        assertThat(join.getIncoming()).containsExactlyInAnyOrder("flowA", "flowB", "flowDefault");
    }

    @Test
    void testParseEventBasedGateway() throws IOException {
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-event-based-gateway.bpmn")));

        BpmnElementModel gw = bpmn.getElement("eventGw");
        assertThat(gw.getType()).isEqualTo(BpmnElementType.EVENT_BASED_GATEWAY);
        assertThat(gw.getOutgoing()).containsExactlyInAnyOrder("flowM", "flowT");
        // its targets are catch events fed by the gateway
        assertThat(bpmn.getElement("msgCatch").getType()).isEqualTo(BpmnElementType.MESSAGE_CATCH_EVENT);
        assertThat(bpmn.getElement("timerCatch").getType()).isEqualTo(BpmnElementType.TIMER_CATCH_EVENT);
    }

    @Test
    void testParseEscalationConstructs() throws IOException {
        // non-interrupting escalation boundary on a sub-process + escalation end inside it
        BpmnProcessDefinitionModel ni = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-escalation-noninterrupting.bpmn")));

        BpmnElementModel boundary = ni.getElement("escBoundary");
        assertThat(boundary.getType()).isEqualTo(BpmnElementType.ESCALATION_BOUNDARY_EVENT);
        assertThat(boundary.getExtensions().getBoundaryEventExtension().isInterrupting()).isFalse();
        assertThat(boundary.getExtensions().getEventDefinition().getCode()).isEqualTo("ESC-1");

        BpmnElementModel escEnd = ni.getElement("subEscEnd");
        assertThat(escEnd.getType()).isEqualTo(BpmnElementType.ESCALATION_END_EVENT);
        assertThat(escEnd.getExtensions().getEventDefinition().getCode()).isEqualTo("ESC-1");

        // interrupting escalation boundary + escalation throw
        BpmnProcessDefinitionModel in = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-escalation-interrupting.bpmn")));

        assertThat(in.getElement("escBoundary").getType()).isEqualTo(BpmnElementType.ESCALATION_BOUNDARY_EVENT);
        assertThat(in.getElement("escBoundary").getExtensions().getBoundaryEventExtension().isInterrupting()).isTrue();

        // a top-level escalation throw
        BpmnProcessDefinitionModel thr = new BpmnParseServiceImpl()
            .parse(Files.readString(Path.of("src/test/files/test-escalation-throw.bpmn")));
        assertThat(thr.getElement("escThrow").getType()).isEqualTo(BpmnElementType.ESCALATION_THROW_EVENT);
    }

    @Test
    void testParseSignalBoundary() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test-signal-boundary.bpmn"));
        BpmnProcessDefinitionModel bpmn = new BpmnParseServiceImpl().parse(bpmnStr);

        BpmnElementModel boundary = bpmn.getElement("sigBoundary");
        assertThat(boundary.getType()).isEqualTo(BpmnElementType.SIGNAL_BOUNDARY_EVENT);
        assertThat(boundary.getExtensions().getBoundaryEventExtension().getAttachedToRef()).isEqualTo("gate1");
        assertThat(boundary.getExtensions().getBoundaryEventExtension().isInterrupting()).isTrue();
        assertThat(boundary.getExtensions().getEventDefinition().getName()).isEqualTo("cancel");
        assertThat(boundary.getOutgoing()).containsExactly("flowBnd");
    }
}
