package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.BpmnProcessStructure;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.BpmnStructureService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessDefinitionResourceTest {

    @Mock
    private ProcessDefinitionService processDefinitionService;

    @Mock
    private FileService fileService;

    @Mock
    private BpmnStructureService bpmnStructureService;

    @Mock
    private ProcessRepository processRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private ProcessDefinitionResource resource;

    @Test
    void addProcessDefinition_delegatesBpmnString() {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn("<bpmn/>");
        ProcessDefinition expected = new ProcessDefinition();
        expected.setId(UUID.randomUUID());
        expected.setKey("test-key");
        when(processDefinitionService.addProcessDefinition("<bpmn/>")).thenReturn(expected);
        // ADR-2: deploy requires SUPER_ADMIN
        when(request.getAttribute("principal")).thenReturn(
            new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN"));

        ProcessDefinition result = resource.addProcessDefinition(dto);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void addProcessDefinition_nonSuperAdmin_returns403() {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn("<bpmn/>");
        when(request.getAttribute("principal")).thenReturn(
            new Principal.UserPrincipal(UUID.randomUUID(), "owner", "USER"));

        assertThatThrownBy(() -> resource.addProcessDefinition(dto))
            .isInstanceOf(ResponseStatusException.class)
            .matches(ex -> ((ResponseStatusException) ex).getStatusCode().equals(HttpStatus.FORBIDDEN));
    }

    @Test
    void addProcessDefinition_noAuth_returns401() {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn("<bpmn/>");
        when(request.getAttribute("principal")).thenReturn(null);

        assertThatThrownBy(() -> resource.addProcessDefinition(dto))
            .isInstanceOf(ResponseStatusException.class)
            .matches(ex -> ((ResponseStatusException) ex).getStatusCode().equals(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void getProcessDefinitions_delegatesParameters() {
        ProcessDefinitionsQueryParameters params = new ProcessDefinitionsQueryParameters();
        PagedDataDTO<ProcessDefinition> expected = new PagedDataDTO<>();
        when(processDefinitionService.getProcessDefinitions(params)).thenReturn(expected);

        PagedDataDTO<ProcessDefinition> result = resource.getProcessDefinitions(params);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getProcessDefinitionById_returnsValueWhenPresent() {
        UUID id = UUID.randomUUID();
        ProcessDefinition pd = new ProcessDefinition();
        pd.setId(id);
        when(processDefinitionService.getProcessDefinitionById(id)).thenReturn(Optional.of(pd));

        ProcessDefinition result = resource.getProcessDefinitionById(id);

        assertThat(result).isSameAs(pd);
    }

    @Test
    void getProcessDefinitionById_throwsNotFoundWhenAbsent() {
        UUID id = UUID.randomUUID();
        when(processDefinitionService.getProcessDefinitionById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resource.getProcessDefinitionById(id))
            .isInstanceOf(ResponseStatusException.class)
            .matches(ex -> ((ResponseStatusException) ex).getStatusCode().equals(HttpStatus.NOT_FOUND));
    }

    @Test
    void getProcessDefinitionXml_delegatesToFileService() throws Exception {
        UUID id = UUID.randomUUID();
        when(fileService.getFileBytes(id)).thenReturn("<bpmn/>");

        String result = resource.getProcessDefinitionXml(id);

        assertThat(result).isEqualTo("<bpmn/>");
    }

    @Test
    void getProcessDefinitionStructure_returnsValueWhenPresent() {
        UUID id = UUID.randomUUID();
        BpmnProcessStructure structure = new BpmnProcessStructure();
        structure.setId(id);
        when(bpmnStructureService.getStructure(id)).thenReturn(Optional.of(structure));

        BpmnProcessStructure result = resource.getProcessDefinitionStructure(id);

        assertThat(result).isSameAs(structure);
    }

    @Test
    void getProcessDefinitionStructure_throwsNotFoundWhenAbsent() {
        UUID id = UUID.randomUUID();
        when(bpmnStructureService.getStructure(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resource.getProcessDefinitionStructure(id))
            .isInstanceOf(ResponseStatusException.class)
            .matches(ex -> ((ResponseStatusException) ex).getStatusCode().equals(HttpStatus.NOT_FOUND));
    }
}
