package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.FormContract;
import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.DBService;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class FormResource implements FormContract {

    private final FormRepository formRepository;
    private final UserTaskRepository userTaskRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final DBService dbService;
    private final HttpServletRequest request;
    private final ObjectMapper objectMapper;

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
        entity.setSchemaJson(dto.getSchema());
        entity.setCreatedAt(Instant.now());
        formRepository.save(entity);

        FormDTO result = new FormDTO();
        result.setKey(entity.getFormKey());
        result.setVersion(entity.getVersion());
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
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, null)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        // For start form, there's no process instance yet — data is empty
        return resolveStartForm(pd.getStartFormKey());
    }

    // --- WO-FORM-2: resolve logic ---

    private TaskFormDTO resolveForm(String formKey, UUID processInstanceId) {
        if (formKey == null || formKey.isBlank()) {
            TaskFormDTO dto = new TaskFormDTO();
            dto.setType("none");
            return dto;
        }

        // External reference (URL)
        if (formKey.startsWith("http://") || formKey.startsWith("https://")) {
            TaskFormDTO dto = new TaskFormDTO();
            dto.setType("external");
            dto.setUrl(formKey);
            return dto;
        }

        // Linked form (form key in form table)
        FormEntity form = formRepository.findTopByFormKeyOrderByVersionDesc(formKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Form schema not found for key: " + formKey));

        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setSchema(form.getSchemaJson());
        dto.setData(prefillData(processInstanceId));
        return dto;
    }

    private TaskFormDTO resolveStartForm(String startFormKey) {
        if (startFormKey == null || startFormKey.isBlank()) {
            TaskFormDTO dto = new TaskFormDTO();
            dto.setType("none");
            return dto;
        }

        if (startFormKey.startsWith("http://") || startFormKey.startsWith("https://")) {
            TaskFormDTO dto = new TaskFormDTO();
            dto.setType("external");
            dto.setUrl(startFormKey);
            return dto;
        }

        FormEntity form = formRepository.findTopByFormKeyOrderByVersionDesc(startFormKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Form schema not found for key: " + startFormKey));

        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setSchema(form.getSchemaJson());
        // No data for start form (no process instance yet)
        return dto;
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
