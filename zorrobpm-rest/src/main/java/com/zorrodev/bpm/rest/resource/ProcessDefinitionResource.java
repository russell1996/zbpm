package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.ProcessDefinitionContract;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.BpmnProcessStructure;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.BpmnStructureService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ProcessDefinitionResource implements ProcessDefinitionContract {

    private final ProcessDefinitionService processDefinitionService;
    private final FileService fileService;
    private final BpmnStructureService bpmnStructureService;
    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final UiUserRepository uiUserRepository;
    private final HttpServletRequest request;

    @Override
    public ProcessDefinition addProcessDefinition(AddProcessDefinitionDTO dto) {
        ProcessDefinition result = processDefinitionService.addProcessDefinition(dto.getBpmn());
        ensureDeployOwnership(result.getKey());
        return result;
    }

    /**
     * Deploy→owner hook (ADR §7): when a new process is deployed, the deployer
     * becomes OWNER if no OWNER exists yet for this definition key.
     * Redeploy of existing key does NOT change ownership.
     */
    private void ensureDeployOwnership(String definitionKey) {
        // Ensure Process entity exists (deploy creates ProcessDefinition, but not Process)
        ProcessEntity process = processRepository.findByDefinitionKey(definitionKey).orElse(null);
        if (process == null) {
            process = new ProcessEntity();
            process.setId(UUID.randomUUID());
            process.setDefinitionKey(definitionKey);
            process.setName(definitionKey);
            process.setCreatedAt(Instant.now());
            process = processRepository.save(process);
        }

        long ownerCount = processMemberRepository.findByProcessId(process.getId()).stream()
            .filter(m -> "OWNER".equals(m.getRole()))
            .count();
        if (ownerCount > 0) return; // Owner already exists — don't change ownership

        // No owner yet — make the deployer the OWNER
        Principal principal = getDeployerPrincipal();
        UUID deployerUserId = (principal instanceof Principal.UserPrincipal u) ? u.userId() : null;
        if (deployerUserId == null) return; // API key deploy — no user to assign

        ProcessMemberEntity owner = new ProcessMemberEntity();
        owner.setProcessId(process.getId());
        owner.setUserId(deployerUserId);
        owner.setRole("OWNER");
        owner.setAddedBy(deployerUserId);
        owner.setAddedAt(Instant.now());
        processMemberRepository.save(owner);
        log.info("Deploy→owner: user={} now OWNER of process key={}", deployerUserId, definitionKey);
    }

    private Principal getDeployerPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    @Override
    public PagedDataDTO<ProcessDefinition> getProcessDefinitions(@ParameterObject ProcessDefinitionsQueryParameters parameters) {
        return processDefinitionService.getProcessDefinitions(parameters);
    }

    @Override
    public ProcessDefinition getProcessDefinitionById(UUID id) {
        return processDefinitionService.getProcessDefinitionById(id).orElseThrow( () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found")) ;
    }

    @Override
    public String getProcessDefinitionXml(UUID id) {
        try {
            String xml = fileService.getFileBytes(id);
            if (xml == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition XML not found");
            }
            return xml;
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition XML not found");
        }
    }

    @Override
    public BpmnProcessStructure getProcessDefinitionStructure(UUID id) {
        return bpmnStructureService.getStructure(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
    }

}
