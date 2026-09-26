package com.zorrodev.bpm.engine.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WO-SEC-29: POF that SyncTaskHandler.ScriptTask does NOT leak PII in INFO logs.
 *
 * RED (before fix): log.info("evaluated to {}", result) writes secret value at INFO.
 * GREEN (after fix): log.debug("evaluated to {}", result) — value NOT in INFO.
 */
class SyncTaskHandlerPiiTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        logAppender.start();
        logger = (Logger) LoggerFactory.getLogger(SyncTaskHandler.class);
        logger.addAppender(logAppender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void pof_scriptTask_secretValue_notInDebugLog() {
        String secret = "secret-salary-99999-debug";

        DBService dbService = mock(DBService.class);
        ScriptService scriptService = mock(ScriptService.class);
        ActivityService activityService = mock(ActivityService.class);
        ElementSupport elementSupport = mock(ElementSupport.class);
        FlowNavigator flowNavigator = mock(FlowNavigator.class);

        when(dbService.getVariables(any())).thenReturn(List.of());
        when(scriptService.evaluateExpression(eq("salary"), any())).thenReturn(secret);
        when(dbService.createActivity(any(UUID.class), any(UUID.class), any(BpmnElementModel.class))).thenReturn(UUID.randomUUID());

        SyncTaskHandler.ScriptTask handler = new SyncTaskHandler.ScriptTask(
            dbService, scriptService, elementSupport, flowNavigator, activityService);

        ExecutionCtx ctx = new ExecutionCtx(UUID.randomUUID(), UUID.randomUUID(), null, null);

        BpmnProcessDefinitionModel bpmn = mock(BpmnProcessDefinitionModel.class);
        BpmnElementModel el = mock(BpmnElementModel.class);

        ScriptTaskExtensionModel ext = mock(ScriptTaskExtensionModel.class);
        when(ext.getScript()).thenReturn("salary");
        BpmnElementExtensionModel extensions = mock(BpmnElementExtensionModel.class);
        when(extensions.getScriptTaskExtension()).thenReturn(ext);
        when(el.getExtensions()).thenReturn(extensions);
        when(el.getType()).thenReturn(BpmnElementType.SCRIPT_TASK);
        when(el.getId()).thenReturn("script1");

        // E6: even at DEBUG, secret must NOT appear
        logger.setLevel(Level.DEBUG);
        logAppender.list.clear();

        handler.handle(ctx, bpmn, el);

        boolean found = logAppender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains(secret));
        assertThat(found)
            .as("Secret value '%s' must NOT appear even in DEBUG logs (WO-SEC-53 E6)", secret)
            .isFalse();
    }

    @Test
    void pof_scriptTask_secretValue_notInInfoLog() {
        String secret = "secret-salary-99999";

        DBService dbService = mock(DBService.class);
        ScriptService scriptService = mock(ScriptService.class);
        ActivityService activityService = mock(ActivityService.class);
        ElementSupport elementSupport = mock(ElementSupport.class);
        FlowNavigator flowNavigator = mock(FlowNavigator.class);

        when(dbService.getVariables(any())).thenReturn(List.of());
        when(scriptService.evaluateExpression(eq("salary"), any())).thenReturn(secret);
        when(dbService.createActivity(any(UUID.class), any(UUID.class), any(BpmnElementModel.class))).thenReturn(UUID.randomUUID());

        SyncTaskHandler.ScriptTask handler = new SyncTaskHandler.ScriptTask(
            dbService, scriptService, elementSupport, flowNavigator, activityService);

        ExecutionCtx ctx = new ExecutionCtx(UUID.randomUUID(), UUID.randomUUID(), null, null);

        BpmnProcessDefinitionModel bpmn = mock(BpmnProcessDefinitionModel.class);
        BpmnElementModel el = mock(BpmnElementModel.class);

        ScriptTaskExtensionModel ext = mock(ScriptTaskExtensionModel.class);
        when(ext.getScript()).thenReturn("salary");
        BpmnElementExtensionModel extensions = mock(BpmnElementExtensionModel.class);
        when(extensions.getScriptTaskExtension()).thenReturn(ext);
        when(el.getExtensions()).thenReturn(extensions);
        when(el.getType()).thenReturn(BpmnElementType.SCRIPT_TASK);
        when(el.getId()).thenReturn("script1");

        handler.handle(ctx, bpmn, el);

        // POF: on INFO level, secret value must NOT appear in logs
        boolean found = logAppender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains(secret));
        assertThat(found)
            .as("Secret value '%s' must NOT appear in INFO logs", secret)
            .isFalse();
    }
}
