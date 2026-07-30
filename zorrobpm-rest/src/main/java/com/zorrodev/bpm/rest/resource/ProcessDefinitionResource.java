package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.ProcessDefinitionContract;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.BpmnProcessStructure;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.BpmnStructureService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ProcessDefinitionResource implements ProcessDefinitionContract {

    private final ProcessDefinitionService processDefinitionService;
    private final FileService fileService;
    private final BpmnStructureService bpmnStructureService;
    private final ProcessRepository processRepository;
    private final AuditLogService auditLogService;
    private final HttpServletRequest request;
    private final EventAuthzResolver eventAuthzResolver;

    private Collection<UUID> resolveAllowedPdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of();
        }
        return eventAuthzResolver.resolve(principal, null);
    }

    private void requirePdAccess(UUID id) {
        Collection<UUID> allowed = resolveAllowedPdIds();
        if (allowed != null && !allowed.contains(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found");
        }
    }

    /**
     * ADR-2: deploy → SUPER_ADMIN only.
     * ADR-2: deploy→owner auto-assignment REMOVED.
     * Process registry entity IS created (needed for member management).
     */
    @Override
    public ProcessDefinition addProcessDefinition(AddProcessDefinitionDTO dto) {
        requireSuperAdmin();
        ProcessDefinition result = processDefinitionService.addProcessDefinition(dto.getBpmn());
        ensureProcessRegistry(result.getKey());
        auditLogService.record(getPrincipal(), "DEPLOY", result.getKey(), result.getId().toString());
        return result;
    }

    /**
     * Ensure Process entity exists in registry for member management.
     * Does NOT assign any OWNER — ADR-2 centralized control plane.
     */
    private void ensureProcessRegistry(String definitionKey) {
        processRepository.findByDefinitionKey(definitionKey).ifPresentOrElse(
            p -> {}, // already exists
            () -> {
                ProcessEntity process = new ProcessEntity();
                process.setId(UUID.randomUUID());
                process.setDefinitionKey(definitionKey);
                process.setName(definitionKey);
                process.setCreatedAt(Instant.now());
                processRepository.save(process);
                log.info("Process registry created for key={}", definitionKey);
            }
        );
    }

    private void requireSuperAdmin() {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deploy requires SUPER_ADMIN");
        }
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    @Override
    public PagedDataDTO<ProcessDefinition> getProcessDefinitions(@ParameterObject ProcessDefinitionsQueryParameters parameters) {
        return processDefinitionService.getProcessDefinitions(parameters, resolveAllowedPdIds());
    }

    @Override
    public ProcessDefinition getProcessDefinitionById(UUID id) {
        requirePdAccess(id);
        return processDefinitionService.getProcessDefinitionById(id).orElseThrow( () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found")) ;
    }

    @Override
    public String getProcessDefinitionXml(UUID id) {
        requirePdAccess(id);
        try {
            return fileService.getFileBytes(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition XML not found"));
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition XML not found");
        }
    }

    @Override
    public BpmnProcessStructure getProcessDefinitionStructure(UUID id) {
        requirePdAccess(id);
        return bpmnStructureService.getStructure(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
    }

}
