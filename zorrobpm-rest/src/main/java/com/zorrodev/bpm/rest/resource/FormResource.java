package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.FormContract;
import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.ElementBindingDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormResolver;
import com.zorrodev.bpm.engine.service.JsonSchemaValidator;
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
    private final ElementArtifactBindingRepository bindingRepository;
    private final DBService dbService;
    private final FormResolver formResolver;
    private final JsonSchemaValidator jsonSchemaValidator;
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }

        // WO-VM-5: validate JSON Schema structure for VARIABLE_SCHEMA
        if (kind == FormArtifactKind.VARIABLE_SCHEMA) {
            java.util.Set<String> errors = jsonSchemaValidator.validateSchema(dto.getSchema());
            if (!errors.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid JSON Schema: " + String.join("; ", errors));
            }
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

        // ADR-6 §D7: try element-artifact binding first (per elementId)
        List<ElementArtifactBindingEntity> bindings = bindingRepository.findByProcessDefinitionId(pd.getId());
        if (!bindings.isEmpty()) {
            // For now, return the first binding's artifact (start event binding)
            ElementArtifactBindingEntity binding = bindings.get(0);
            return resolveByBinding(binding);
        }

        // Fallback to scalar startFormKey (back-compat)
        return resolveStartForm(pd.getStartFormKey());
    }

    @Override
    @Transactional
    public ElementBindingDTO createElementBinding(String key, CreateElementBindingDTO dto) {
        // SUPER_ADMIN only
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can create element bindings");
        }

        if (dto.getElementId() == null || dto.getElementId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "elementId is required");
        }
        if (dto.getArtifactKey() == null || dto.getArtifactKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "artifactKey is required");
        }

        // Resolve latest version of process definition
        Integer maxPdVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxPdVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        // Resolve latest version of artifact (pin to this version)
        FormEntity artifact = formRepository.findTopByFormKeyOrderByVersionDesc(dto.getArtifactKey())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found: " + dto.getArtifactKey()));

        // Upsert: delete existing binding for same PD + elementId
        bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), dto.getElementId())
            .ifPresent(bindingRepository::delete);

        ElementArtifactBindingEntity binding = new ElementArtifactBindingEntity();
        binding.setId(UUID.randomUUID());
        binding.setProcessDefinitionId(pd.getId());
        binding.setProcessDefinitionVersion(pd.getVersion());
        binding.setElementId(dto.getElementId());
        binding.setArtifactKey(dto.getArtifactKey());
        binding.setArtifactVersion(artifact.getVersion());
        binding.setCreatedAt(Instant.now());
        bindingRepository.save(binding);

        ElementBindingDTO result = new ElementBindingDTO();
        result.setId(binding.getId());
        result.setElementId(binding.getElementId());
        result.setArtifactKey(binding.getArtifactKey());
        result.setArtifactVersion(binding.getArtifactVersion());
        result.setProcessDefinitionId(binding.getProcessDefinitionId());
        result.setProcessDefinitionVersion(binding.getProcessDefinitionVersion());
        return result;
    }

    @Override
    public List<ElementBindingDTO> listElementBindings(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        return bindingRepository.findByProcessDefinitionId(pd.getId()).stream()
            .map(b -> {
                ElementBindingDTO dto = new ElementBindingDTO();
                dto.setId(b.getId());
                dto.setElementId(b.getElementId());
                dto.setArtifactKey(b.getArtifactKey());
                dto.setArtifactVersion(b.getArtifactVersion());
                dto.setProcessDefinitionId(b.getProcessDefinitionId());
                dto.setProcessDefinitionVersion(b.getProcessDefinitionVersion());
                return dto;
            })
            .toList();
    }

    @Override
    @Transactional
    public void deleteElementBinding(String key, String elementId) {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can delete element bindings");
        }

        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        bindingRepository.deleteByProcessDefinitionIdAndElementId(pd.getId(), elementId);
    }

    // --- WO-FORM-2: resolve logic ---

    private TaskFormDTO resolveForm(String formKey, UUID processInstanceId) {
        return formResolver.resolveTaskForm(formKey, prefillData(processInstanceId));
    }

    private TaskFormDTO resolveStartForm(String startFormKey) {
        return formResolver.resolveTaskForm(startFormKey, null);
    }

    private TaskFormDTO resolveByBinding(ElementArtifactBindingEntity binding) {
        // ADR-6 §D8: pin to artifact_version from binding
        FormEntity form = formRepository.findByFormKeyAndVersion(binding.getArtifactKey(), binding.getArtifactVersion())
            .orElse(null);
        if (form == null) {
            // Fallback: try latest version
            return resolveStartForm(binding.getArtifactKey());
        }
        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
        dto.setSchema(form.getSchemaJson());
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
