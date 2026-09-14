package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.SchemaMapDTO;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.JsonSchemaValidator;
import com.zorrodev.bpm.engine.service.SchemaMapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * WO-DEBT-4e note: this test used to target {@code FormResource.getSchemaMap} directly.
 * The logic moved 1:1 to {@link SchemaMapOperationsImpl} (FormResource is now a delegate),
 * so the test was retargeted at the impl — same intent (log-and-continue on unparsable PD),
 * same assertions. File/class name kept to preserve history (G20).
 */
@ExtendWith(MockitoExtension.class)
class FormResourceSchemaMapLogTest {

    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;
    @Mock
    private ElementArtifactBindingRepository bindingRepository;
    @Mock
    private BpmnService bpmnService;
    @Mock
    private FormRepository formRepository;
    @Mock
    private FormAccessSupport formAccessSupport;
    @Mock
    private tools.jackson.databind.ObjectMapper objectMapper;
    @Mock
    private JsonSchemaValidator jsonSchemaValidator;

    private SchemaMapOperationsImpl schemaMapOperations;

    // WO-DEBT-7 S5: the impl is a thin facade — construct it over a real service
    // with the same mocks (assertions below unchanged).
    @BeforeEach
    void setup() {
        SchemaMapService schemaMapService = new SchemaMapService(processDefinitionRepository,
            formRepository, bindingRepository, bpmnService, objectMapper, jsonSchemaValidator);
        schemaMapOperations = new SchemaMapOperationsImpl(schemaMapService, formAccessSupport);
    }

    @Test
    void getSchemaMap_continuesWhenOneProcessDefinitionFailsToParse() {
        // Given: two process definitions, one valid and one broken
        UUID validId = UUID.randomUUID();
        UUID brokenId = UUID.randomUUID();

        ProcessDefinitionEntity validPd = new ProcessDefinitionEntity();
        validPd.setId(validId);
        validPd.setKey("valid-process");
        validPd.setVersion(1);

        ProcessDefinitionEntity brokenPd = new ProcessDefinitionEntity();
        brokenPd.setId(brokenId);
        brokenPd.setKey("broken-process");
        brokenPd.setVersion(1);

        when(processDefinitionRepository.findMaxByKey("test-form")).thenReturn(java.util.Optional.of(1));
        when(processDefinitionRepository.findByKeyAndVersion("test-form", 1)).thenReturn(java.util.Optional.of(validPd));
        when(bindingRepository.findByProcessDefinitionId(validId)).thenReturn(List.of());
        when(processDefinitionRepository.findAll()).thenReturn(List.of(validPd, brokenPd));

        // Valid PD returns a model with a start event
        BpmnProcessDefinitionModel validModel = new BpmnProcessDefinitionModel();
        BpmnElementModel startEvent = new BpmnElementModel();
        startEvent.setId("startEvent");
        startEvent.setType(BpmnElementType.START_EVENT);
        validModel.addElement(startEvent);
        validModel.setStartEvent(startEvent);

        when(bpmnService.getProcessDefinitionModelById(validId)).thenReturn(validModel);

        // Broken PD throws an exception
        when(bpmnService.getProcessDefinitionModelById(brokenId))
            .thenThrow(new RuntimeException("Invalid BPMN XML"));

        // The caller is allowed to read the valid PD (WO-SEC-59 #7 gate passes by default mock)
        // When: getSchemaMap is called
        SchemaMapDTO result = schemaMapOperations.getSchemaMap("test-form");

        // Then: method completes without exception, returns result for valid PD
        assertThat(result).isNotNull();
        assertThat(result.getElements()).hasSize(1);
        assertThat(result.getElements().get(0).getElementId()).isEqualTo("startEvent");
    }
}
