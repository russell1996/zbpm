package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WO-DEBT-4d — TaskForm domain slice. Byte-for-byte move of the 2 endpoints + 4
 * form-resolve helpers from {@code FormResource} (only {@code requireRuntimePdAccess}/
 * {@code requirePdAccess} re-pointed at {@link FormAccessSupport}); no
 * {@code @Transactional} — both endpoints are read-only, as in the original.
 * WO-ACL-1 / ADR-6 §D7 / ADR-6 §D8 comments kept verbatim.
 */
@Service
@RequiredArgsConstructor
public class TaskFormOperationsImpl implements TaskFormOperations {

    private final UserTaskRepository userTaskRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ElementArtifactBindingRepository bindingRepository;
    private final FormRepository formRepository;
    private final FormResolver formResolver;
    private final DBService dbService;
    private final FormAccessSupport formAccessSupport;

    @Override
    public TaskFormDTO getUserTaskForm(UUID id) {
        UserTaskEntity task = userTaskRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found"));
        ProcessInstanceEntity pi = processInstanceRepository.findById(task.getProcessInstanceId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        // WO-ACL-1: task form of a concrete instance = runtime read (variables prefill)
        formAccessSupport.requireRuntimePdAccess(pi.getProcessDefinitionId());
        // WO-C8-22: a linked formId wins over formKey (docs list the reference kinds as
        // mutually exclusive; the specific linked id beats the generic key on invalid
        // models carrying both). formKey/externalReference paths below are untouched.
        if (task.getFormId() != null && !task.getFormId().isBlank()) {
            return formResolver.resolveTaskFormByFormId(task.getFormId(), prefillData(task.getProcessInstanceId()));
        }
        return resolveForm(task.getFormKey(), task.getProcessInstanceId());
    }

    @Override
    public TaskFormDTO getStartForm(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        formAccessSupport.requirePdAccess(pd.getId());

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
}
