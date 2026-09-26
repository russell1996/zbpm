package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-DIFF-4 — join collapse on a ROOT token. A non-interrupting boundary forks on a CHILD
 * token of the host's token, so the host branch may still run on the ROOT host token. When
 * that branch arrives at a join LAST, {@code getParentId()} is null — collapsing to it
 * would continue the tail on a null token (live {@code DataIntegrityViolationException}:
 * NULL not allowed for column "TOKEN"). The join must continue on the arriving token
 * itself instead.
 */
@ExtendWith(MockitoExtension.class)
class GatewayJoinRootTokenCollapseTest {

    @Mock
    private DBService dbService;
    @Mock
    private FlowNavigator flowNavigator;
    @Mock
    private ScriptService scriptService;
    @Mock
    private TokenExecutor executor;
    @Mock
    private ExecutionContext executionContext;

    private Token rootToken(UUID id) {
        Token t = new Token();
        t.setId(id);
        t.setParentId(null);
        return t;
    }

    private BpmnProcessDefinitionModel bpmnWithJoin(String joinId, List<String> incomings, String outgoing) {
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        BpmnElementModel join = new BpmnElementModel();
        join.setId(joinId);
        join.getIncoming().addAll(incomings);
        join.getOutgoing().add(outgoing);
        bpmn.addElement(join);
        BpmnElementModel after = new BpmnElementModel();
        after.setId("after");
        bpmn.addElement(after);
        BpmnFlowModel flow = new BpmnFlowModel();
        flow.setFlowId(outgoing);
        flow.setSourceRef(joinId);
        flow.setTargetRef("after");
        bpmn.addFlow(flow);
        return bpmn;
    }

    @Test
    void parallelJoin_rootTokenArrivesLast_continuesOnArrivingToken() {
        UUID pi = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        BpmnProcessDefinitionModel bpmn = bpmnWithJoin("join", List.of("f1", "f2"), "flow-join-after");
        BpmnElementModel join = bpmn.getElement("join");
        join.setType(BpmnElementType.PARALLEL_GATEWAY);

        when(dbService.getParallelGatewayArrivedFlows(pi, "join")).thenReturn(Set.of("f1", "f2"));
        when(dbService.getToken(rootId)).thenReturn(rootToken(rootId));
        when(dbService.createActivity(eq(pi), any(UUID.class), any(BpmnElementModel.class))).thenReturn(UUID.randomUUID());

        new ParallelGatewayHandler(dbService, flowNavigator)
            .handle(new ExecutionCtx(pi, rootId, executor, executionContext), bpmn, join);

        ArgumentCaptor<UUID> tokenCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(dbService).createActivity(eq(pi), tokenCaptor.capture(), any(BpmnElementModel.class));
        assertThat(tokenCaptor.getValue())
            .as("root arrival must continue on itself, not on a null parent")
            .isEqualTo(rootId);
        verify(executor).execute(eq(pi), eq(rootId), eq(bpmn), any());
    }

    @Test
    void inclusiveJoin_rootTokenArrivesLast_continuesOnArrivingToken() {
        UUID pi = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        BpmnProcessDefinitionModel bpmn = bpmnWithJoin("join", List.of("f1", "f2"), "flow-join-after");
        BpmnElementModel join = bpmn.getElement("join");
        join.setType(BpmnElementType.INCLUSIVE_GATEWAY);

        when(dbService.getInclusiveExpected(pi, "join")).thenReturn(2);
        when(dbService.getParallelGatewayArrivedFlows(pi, "join")).thenReturn(Set.of("f1", "f2"));
        when(dbService.getToken(rootId)).thenReturn(rootToken(rootId));
        when(dbService.createActivity(eq(pi), any(UUID.class), any(BpmnElementModel.class))).thenReturn(UUID.randomUUID());

        new InclusiveGatewayHandler(dbService, flowNavigator, scriptService)
            .handle(new ExecutionCtx(pi, rootId, executor, executionContext), bpmn, join);

        ArgumentCaptor<UUID> tokenCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(dbService).createActivity(eq(pi), tokenCaptor.capture(), any(BpmnElementModel.class));
        assertThat(tokenCaptor.getValue())
            .as("root arrival must continue on itself, not on a null parent")
            .isEqualTo(rootId);
        verify(executor).execute(eq(pi), eq(rootId), eq(bpmn), any());
    }
}
