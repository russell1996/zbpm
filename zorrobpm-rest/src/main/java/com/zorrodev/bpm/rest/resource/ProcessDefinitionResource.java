package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.ProcessDefinitionContract;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.contract.model.BpmnProcessStructure;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnStructureService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
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
    private final AuthorizationService authorizationService;
    private final BpmnParseService bpmnParseService;
    private final HttpServletRequest request;
    private final HttpServletResponse httpResponse;
    private final EventAuthzResolver eventAuthzResolver;

    private Collection<UUID> resolveAllowedPdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of();
        }
        return eventAuthzResolver.visibleDefinitionIds(principal, null);
    }

    private void requirePdAccess(UUID id) {
        Collection<UUID> allowed = resolveAllowedPdIds();
        if (allowed != null && !allowed.contains(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found");
        }
    }

    @Override
    public void archiveProcess(String key) {
        requireDeployAccessByKey(key);
        processDefinitionService.archiveProcess(key);
        auditLogService.record(getPrincipal(), "ARCHIVE_PROCESS", key, key);
    }

    @Override
    public void unarchiveProcess(String key) {
        requireDeployAccessByKey(key);
        processDefinitionService.unarchiveProcess(key);
        auditLogService.record(getPrincipal(), "UNARCHIVE_PROCESS", key, key);
    }

    /**
     * ADR-2: deploy → SUPER_ADMIN only.
     * ADR-2: deploy→owner auto-assignment REMOVED.
     * Process registry entity IS created (needed for member management).
     */
    /**
     * WO-API-1 (API-1): create → 201 + Location (контракт не тронут).
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public ProcessDefinition addProcessDefinition(@Valid AddProcessDefinitionDTO dto) {
        requireSuperAdmin();
        ProcessDefinition result = processDefinitionService.addProcessDefinition(dto.getBpmn());
        ensureProcessRegistry(result.getKey());
        auditLogService.record(getPrincipal(), "DEPLOY", result.getKey(), result.getId().toString());
        // WO-API-1: Location через nullable-response (unit-тесты с @InjectMocks без
        // response-контекста не должны NPE — заголовок опционален, статус 201 главный).
        if (httpResponse != null) {
            httpResponse.setHeader("Location", "/process-definitions/" + result.getId());
        }
        return result;
    }

    /**
     * WO-ACL-4 (ADR-8 п.3): new version of an EXISTING process, authorized by the target
     * definition {@code id} from the path — the resource the server knows before parsing the
     * file. Order is the point: authorize → parse → verify key → save. A model whose key does
     * not match the target process is rejected BEFORE anything is persisted, so the owner of
     * process A cannot capture process B by uploading a model with B's key.
     */
    @Override
    public ProcessDefinition addProcessDefinitionVersion(UUID id, @Valid AddProcessDefinitionDTO dto) {
        ProcessDefinition target = requireDeployAccess(id);

        BpmnProcessDefinitionModel model;
        try {
            model = bpmnParseService.parse(dto.getBpmn());
        } catch (BpmnParseException e) {
            // Deliberately NOT exposing parser internals (WO-SEC-33 pattern).
            log.warn("Version rejected: BPMN does not parse (targetId={})", id);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "BPMN could not be parsed — fix the XML and resubmit");
        }

        String targetKey = target.getKey();
        if (!targetKey.equals(model.getKey())) {
            // WO-ACL-12: stable code + structured params for the frontend locale; the message
            // stays as-is for logs and API clients.
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROCESS_KEY_MISMATCH",
                "The process key inside the BPMN XML ('" + model.getKey() + "') does not match "
                    + "the key of the target process ('" + targetKey + "'). The key cannot be "
                    + "changed — update the model, not the key.",
                Map.of("xmlKey", model.getKey(), "targetKey", targetKey));
        }

        ProcessDefinition result = processDefinitionService.addProcessDefinition(dto.getBpmn());
        auditLogService.record(getPrincipal(), "DEPLOY", result.getKey(), result.getId().toString());
        return result;
    }

    private void requireDeployAccessByKey(String key) {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        // Ensure registry exists
        processRepository.findByDefinitionKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found: " + key));
        if (!authorizationService.canOperate(principal, key, AuthorizationService.Action.DEPLOY)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Archiving requires OWNER or DESIGNER role on this process");
        }
    }

    /**
     * WO-ACL-4: DEPLOY on the target process (ADR-8 п.3 — OWNER/DESIGNER; SUPER_ADMIN always).
     * Unknown {@code id} → 404; authenticated user without the role → 403; no principal → 401.
     */
    private ProcessDefinition requireDeployAccess(UUID id) {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        ProcessDefinition target = processDefinitionService.getProcessDefinitionById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        if (!authorizationService.canOperate(principal, target.getKey(), AuthorizationService.Action.DEPLOY)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Deploying a new version requires OWNER or DESIGNER role on this process");
        }
        return target;
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
