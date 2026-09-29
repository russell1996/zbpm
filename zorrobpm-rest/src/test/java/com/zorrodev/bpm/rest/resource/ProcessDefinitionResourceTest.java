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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessDefinitionResourceTest {

    @Mock private ProcessDefinitionService processDefinitionService;
    @Mock private FileService fileService;
    @Mock private BpmnStructureService bpmnStructureService;
    @Mock private ProcessRepository processRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private HttpServletRequest request;
    @Mock private EventAuthzResolver eventAuthzResolver;

    @InjectMocks
    private ProcessDefinitionResource resource;

    private static Principal superAdmin() {
        return new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    private void stubSuperAdmin() {
        when(request.getAttribute("principal")).thenReturn(superAdmin());
        when(eventAuthzResolver.visibleDefinitionIds(any(), any())).thenReturn(null);
    }

    // --- addProcessDefinition ---

    @Test
    void addProcessDefinition_delegatesBpmnString() {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn("<bpmn/>");
        ProcessDefinition expected = new ProcessDefinition();
        expected.setId(UUID.randomUUID());
        expected.setKey("test-key");
        when(processDefinitionService.addProcessDefinition("<bpmn/>")).thenReturn(expected);
        when(request.getAttribute("principal")).thenReturn(superAdmin());

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

    // --- getProcessDefinitions ---

    @Test
    void getProcessDefinitions_delegatesParameters() {
        ProcessDefinitionsQueryParameters params = new ProcessDefinitionsQueryParameters();
        PagedDataDTO<ProcessDefinition> expected = new PagedDataDTO<>();
        stubSuperAdmin();
        when(processDefinitionService.getProcessDefinitions(params, null)).thenReturn(expected);

        PagedDataDTO<ProcessDefinition> result = resource.getProcessDefinitions(params);
        assertThat(result).isSameAs(expected);
    }

    // --- getProcessDefinitionById ---

    @Test
    void getProcessDefinitionById_returnsValueWhenPresent() {
        UUID id = UUID.randomUUID();
        ProcessDefinition pd = new ProcessDefinition();
        pd.setId(id);
        stubSuperAdmin();
        when(processDefinitionService.getProcessDefinitionById(id)).thenReturn(Optional.of(pd));

        ProcessDefinition result = resource.getProcessDefinitionById(id);
        assertThat(result).isSameAs(pd);
    }

    @Test
    void getProcessDefinitionById_throwsNotFoundWhenAbsent() {
        UUID id = UUID.randomUUID();
        stubSuperAdmin();
        when(processDefinitionService.getProcessDefinitionById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resource.getProcessDefinitionById(id))
            .isInstanceOf(ResponseStatusException.class)
            .matches(ex -> ((ResponseStatusException) ex).getStatusCode().equals(HttpStatus.NOT_FOUND));
    }

    @Test
    void getProcessDefinitionById_deniedPdId_throws404() {
        UUID pdIdA = UUID.randomUUID();
        UUID pdIdB = UUID.randomUUID();
        when(request.getAttribute("principal")).thenReturn(new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(), java.util.Map.of(pdIdA, new Principal.Grant(Set.of("READ"), false))));
        when(eventAuthzResolver.visibleDefinitionIds(any(), any())).thenReturn(Set.of(pdIdA));

        assertThatThrownBy(() -> resource.getProcessDefinitionById(pdIdB))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }

    // --- getProcessDefinitionXml ---

    @Test
    void getProcessDefinitionXml_delegatesToFileService() throws Exception {
        UUID id = UUID.randomUUID();
        stubSuperAdmin();
        when(fileService.getFileBytes(id)).thenReturn(Optional.of("<bpmn/>"));

        String result = resource.getProcessDefinitionXml(id);
        assertThat(result).isEqualTo("<bpmn/>");
    }

    @Test
    void getProcessDefinitionXml_deniedPdId_throws404() {
        UUID pdIdA = UUID.randomUUID();
        UUID pdIdB = UUID.randomUUID();
        when(request.getAttribute("principal")).thenReturn(new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(), java.util.Map.of(pdIdA, new Principal.Grant(Set.of("READ"), false))));
        when(eventAuthzResolver.visibleDefinitionIds(any(), any())).thenReturn(Set.of(pdIdA));

        assertThatThrownBy(() -> resource.getProcessDefinitionXml(pdIdB))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }

    // --- getProcessDefinitionStructure ---

    @Test
    void getProcessDefinitionStructure_returnsValueWhenPresent() {
        UUID id = UUID.randomUUID();
        BpmnProcessStructure structure = new BpmnProcessStructure();
        structure.setId(id);
        stubSuperAdmin();
        when(bpmnStructureService.getStructure(id)).thenReturn(Optional.of(structure));

        BpmnProcessStructure result = resource.getProcessDefinitionStructure(id);
        assertThat(result).isSameAs(structure);
    }

    @Test
    void getProcessDefinitionStructure_throwsNotFoundWhenAbsent() {
        UUID id = UUID.randomUUID();
        stubSuperAdmin();
        when(bpmnStructureService.getStructure(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resource.getProcessDefinitionStructure(id))
            .isInstanceOf(ResponseStatusException.class)
            .matches(ex -> ((ResponseStatusException) ex).getStatusCode().equals(HttpStatus.NOT_FOUND));
    }

    @Test
    void getProcessDefinitionStructure_deniedPdId_throws404() {
        UUID pdIdA = UUID.randomUUID();
        UUID pdIdB = UUID.randomUUID();
        when(request.getAttribute("principal")).thenReturn(new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(), java.util.Map.of(pdIdA, new Principal.Grant(Set.of("READ"), false))));
        when(eventAuthzResolver.visibleDefinitionIds(any(), any())).thenReturn(Set.of(pdIdA));

        assertThatThrownBy(() -> resource.getProcessDefinitionStructure(pdIdB))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }
}
