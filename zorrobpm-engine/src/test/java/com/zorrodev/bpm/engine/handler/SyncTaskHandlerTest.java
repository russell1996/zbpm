package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BusinessRuleExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ScriptTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.impl.ActivityServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncTaskHandlerTest {

    @Mock private DBService dbService;
    @Mock private ScriptService scriptService;
    @Mock private DmnService dmnService;
    @Mock private BpmnService bpmnService;
    @Mock private ActivityServiceImpl activityService;
    @Mock private ElementSupport elementSupport;
    @Mock private FlowNavigator flowNavigator;

    private SyncTaskHandler.ScriptTask scriptTask;
    private SyncTaskHandler.BusinessRuleTask businessRuleTask;

    @BeforeEach
    void setUp() {
        scriptTask = new SyncTaskHandler.ScriptTask(dbService, scriptService, elementSupport, flowNavigator, activityService);
        businessRuleTask = new SyncTaskHandler.BusinessRuleTask(dbService, scriptService, dmnService, elementSupport, flowNavigator);
    }

    @Test
    void scriptTask_evaluatesAndCompletes() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("script1");
        el.setType(BpmnElementType.SCRIPT_TASK);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        ScriptTaskExtensionModel ext = new ScriptTaskExtensionModel();
        ext.setScript("x + 1");
        ext.setResultVariable("result");
        BpmnElementExtensionModel elExt = new BpmnElementExtensionModel();
        elExt.setScriptTaskExtension(ext);
        el.setExtensions(elExt);

        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);
        when(dbService.getVariables(piId)).thenReturn(List.of());
        when(scriptService.evaluateExpression(eq("x + 1"), any())).thenReturn(42L);
        when(elementSupport.toProcessVariable(eq("result"), eq(42L))).thenReturn(new ProcessVariable());

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        scriptTask.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dbService).setVariables(eq(piId), any());
        verify(dbService).completeActivity(activityId);
        verify(flowNavigator).proceedToOutgoing(eq(piId), eq(tokenId), eq(bpmn), eq(el), isNull());
        verify(activityService).triggerConditionalEvents(piId);
    }

    @Test
    void businessRuleTask_dmnEvaluate() {
        UUID piId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        BpmnElementModel el = new BpmnElementModel();
        el.setId("brt1");
        el.setType(BpmnElementType.BUSINESS_RULE_TASK);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();

        BusinessRuleExtensionModel ext = new BusinessRuleExtensionModel();
        ext.setDecisionId("decision1");
        ext.setResultVariable("output");
        BpmnElementExtensionModel elExt = new BpmnElementExtensionModel();
        elExt.setBusinessRuleExtension(ext);
        el.setExtensions(elExt);

        when(dbService.createActivity(eq(piId), eq(tokenId), eq(el))).thenReturn(activityId);
        when(dbService.getVariables(piId)).thenReturn(List.of());
        // WO-C8-2: handler resolves decisionId via elementSupport — a literal passes through as-is.
        when(elementSupport.resolveExpression(eq("decision1"), eq(piId))).thenReturn("decision1");
        // WO-DIFF-8: handler probes existence before evaluating — the decision is deployed here.
        when(dmnService.decisionExists(eq("decision1"))).thenReturn(true);
        when(dmnService.evaluate(eq("decision1"), any())).thenReturn("approved");
        when(elementSupport.toProcessVariable(eq("output"), eq("approved"))).thenReturn(new ProcessVariable());

        ExecutionCtx ctx = new ExecutionCtx(piId, tokenId, null, null);
        businessRuleTask.handle(ctx, bpmn, el);

        verify(dbService).createActivity(piId, tokenId, el);
        verify(dmnService).evaluate("decision1", List.of());
        verify(dbService).setVariables(eq(piId), any());
        verify(dbService).completeActivity(activityId);
        verify(flowNavigator).proceedToOutgoing(eq(piId), eq(tokenId), eq(bpmn), eq(el), isNull());
    }
}
