package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.FormContract;
import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class FormResource implements FormContract {

    private final FormRepository formRepository;
    private final UserTaskRepository userTaskRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final DBService dbService;
    private final FormResolver formResolver;
    private final HttpServletRequest request;
    private final ObjectMapper objectMapper;

    @Override
    public List<FormDTO> listForms() {
        return formRepository.findLatestVersions().stream().map(entity -> {
            FormDTO dto = new FormDTO();
            dto.setKey(entity.getFormKey());
            dto.setVersion(entity.getVersion());
            dto.setKind(entity.getKind() != null ? entity.getKind().name() : null);
            dto.setSchema(entity.getSchemaJson());
            return dto;
        }).toList();
    }

    @Override
    @Transactional
    public FormDTO deployForm(@RequestBody DeployFormDTO dto) {
        // WO-FORM-1: only SUPER_ADMIN can deploy forms
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deploy requires SUPER_ADMIN");
        }

        if (dto.getKey() == null || dto.getKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form key is required");
        }
        if (dto.getKind() == null || dto.getKind().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind is required");
        }
        FormArtifactKind kind;
        try {
            kind = FormArtifactKind.valueOf(dto.getKind());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid kind: " + dto.getKind() + ". Must be FORM_JS or VARIABLE_SCHEMA");
        }
        if (dto.getSchema() == null || dto.getSchema().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form schema is required");
        }

        // Validate JSON
        try {
            objectMapper.readValue(dto.getSchema(), Object.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON schema");
        }

        // Versioning: version = max + 1
        int maxVersion = formRepository.findMaxVersionByFormKey(dto.getKey());
        FormEntity entity = new FormEntity();
        entity.setId(UUID.randomUUID());
        entity.setFormKey(dto.getKey());
        entity.setVersion(maxVersion + 1);
        entity.setKind(kind);
        entity.setSchemaJson(dto.getSchema());
        entity.setCreatedAt(Instant.now());
        formRepository.save(entity);

        FormDTO result = new FormDTO();
        result.setKey(entity.getFormKey());
        result.setVersion(entity.getVersion());
        result.setKind(entity.getKind().name());
        result.setSchema(entity.getSchemaJson());
        return result;
    }

    @Override
    public FormDTO getForm(String key) {
        FormEntity entity = formRepository.findTopByFormKeyOrderByVersionDesc(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form not found"));

        FormDTO dto = new FormDTO();
        dto.setKey(entity.getFormKey());
        dto.setVersion(entity.getVersion());
        dto.setKind(entity.getKind() != null ? entity.getKind().name() : null);
        dto.setSchema(entity.getSchemaJson());
        return dto;
    }

    // --- WO-FORM-2: form resolve endpoints ---

    @Override
    public TaskFormDTO getUserTaskForm(UUID id) {
        UserTaskEntity task = userTaskRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found"));
        return resolveForm(task.getFormKey(), task.getProcessInstanceId());
    }

    @Override
    public TaskFormDTO getStartForm(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        // For start form, there's no process instance yet — data is empty
        return resolveStartForm(pd.getStartFormKey());
    }

    // --- WO-FORM-2: resolve logic ---

    private TaskFormDTO resolveForm(String formKey, UUID processInstanceId) {
        return formResolver.resolveTaskForm(formKey, prefillData(processInstanceId));
    }

    private TaskFormDTO resolveStartForm(String startFormKey) {
        return formResolver.resolveTaskForm(startFormKey, null);
    }

    private Map<String, String> prefillData(UUID processInstanceId) {
        Map<String, String> data = new LinkedHashMap<>();
        if (processInstanceId == null) return data;
        java.util.List<ProcessVariable> vars = dbService.getVariables(processInstanceId);
        for (ProcessVariable v : vars) {
            if (v.getName() != null && v.getValue() != null) {
                data.put(v.getName(), v.getValue());
            }
        }
        return data;
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }
}
