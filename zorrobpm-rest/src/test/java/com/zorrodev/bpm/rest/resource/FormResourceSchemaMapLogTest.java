package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.SchemaMapDTO;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.BpmnService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormResourceSchemaMapLogTest {

    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;
    @Mock
    private ElementArtifactBindingRepository bindingRepository;
    @Mock
    private BpmnService bpmnService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private EventAuthzResolver eventAuthzResolver;

    @InjectMocks
    private FormResource formResource;

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
        when(bindingRepository.findAll()).thenReturn(List.of());
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

        // The caller is an authenticated principal that is allowed to read the valid PD
        // (WO-SEC-59 #7 added an authz gate to getSchemaMap).
        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "tester", "USER");
        when(request.getAttribute("principal")).thenReturn(principal);
        when(eventAuthzResolver.visibleDefinitionIds(any(), any())).thenReturn(Set.of(validId));

        // When: getSchemaMap is called
        SchemaMapDTO result = formResource.getSchemaMap("test-form");

        // Then: method completes without exception, returns result for valid PD
        assertThat(result).isNotNull();
        assertThat(result.getElements()).hasSize(1);
        assertThat(result.getElements().get(0).getElementId()).isEqualTo("startEvent");
    }
}