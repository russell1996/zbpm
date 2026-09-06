package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
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
import com.zorrodev.bpm.engine.service.BpmnService;
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
    private final BpmnService bpmnService;
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
            // WO-C8-23: bindingType="deployment" pins the form version deployed together
            // with this instance's process version; anything else (latest/absent) keeps
            // the C8-22 path byte-identical.
            if ("deployment".equals(task.getBindingType())) {
                UUID deploymentId = processDefinitionRepository.findById(pi.getProcessDefinitionId())
                    .map(ProcessDefinitionEntity::getDeploymentId)
                    .orElse(null);
                return formResolver.resolveTaskFormByFormIdAndDeployment(
                    task.getFormId(), deploymentId, prefillData(task.getProcessInstanceId()));
            }
            // WO-C8-31: bindingType="versionTag" pins the latest form version carrying
            // the tag from this element's formDefinition (WO-C8-27: top-level .form JSON
            // field). The tag value is static per element — read from the cached model
            // of THIS instance's process version (no new row column, C8-26 pattern);
            // absent tag → explicit 404 from the resolver, never silent latest.
            if ("versionTag".equals(task.getBindingType())) {
                return formResolver.resolveTaskFormByFormIdAndVersionTag(
                    task.getFormId(),
                    userTaskVersionTag(pi.getProcessDefinitionId(), task.getBpmnElementId()),
                    prefillData(task.getProcessInstanceId()));
            }
            return formResolver.resolveTaskFormByFormId(task.getFormId(), prefillData(task.getProcessInstanceId()));
        }
        return resolveForm(task.getFormKey(), task.getProcessInstanceId());
    }

    /**
     * WO-C8-31: static {@code versionTag} of a user-task element
     * ({@code zeebe:formDefinition/@versionTag}, parsed into the element model).
     * Null-safe: unknown element/model → null → the resolver 404s explicitly.
     */
    private String userTaskVersionTag(UUID processDefinitionId, String elementId) {
        if (processDefinitionId == null || elementId == null) {
            return null;
        }
        var element = bpmnService.getProcessDefinitionModelById(processDefinitionId).getElement(elementId);
        if (element == null || element.getExtensions() == null
            || element.getExtensions().getUserTaskExtension() == null) {
            return null;
        }
        return element.getExtensions().getUserTaskExtension().getVersionTag();
    }

    @Override
    public TaskFormDTO getStartForm(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        formAccessSupport.requirePdAccess(pd.getId());

        // WO-C8-26: linked formDefinition of the plain start event wins over every
        // legacy path (docs: the Modeler offers one Form type at a time — linked XOR
        // embedded XOR custom — so the structured reference beats the hand-written
        // zeebe:properties formKey on invalid models carrying both). Read from the
        // cached PD model (BpmnService cache; version-correct: this version's id);
        // the deployment pin uses this version's deployment_id.
        // ADR-6 §D7 bindings and scalar startFormKey below are byte-identical fallbacks.
        BpmnProcessDefinitionModel startModel =
            bpmnService.getProcessDefinitionModelById(pd.getId());
        String startFormId = startModel.getStartFormId();
        if (startFormId != null && !startFormId.isBlank()) {
            if ("deployment".equals(startModel.getStartFormBindingType())) {
                return formResolver.resolveTaskFormByFormIdAndDeployment(
                    startFormId, pd.getDeploymentId(), null);
            }
            // WO-C8-31: same versionTag pin for start forms (tag parsed in C8-26,
            // resolver shared — no duplication, boundary of this WO).
            if ("versionTag".equals(startModel.getStartFormBindingType())) {
                return formResolver.resolveTaskFormByFormIdAndVersionTag(
                    startFormId, startModel.getStartFormVersionTag(), null);
            }
            return formResolver.resolveTaskFormByFormId(startFormId, null);
        }

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
