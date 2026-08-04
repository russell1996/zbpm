package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks
    private EventTrigger eventTrigger;

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
}