package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormResolver;
import com.zorrodev.bpm.engine.service.TaskFormDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
 * WO-DEBT-7 S2 — JPA moved to {@link TaskFormDataService}; this class is a thin
 * facade (no direct persistence imports), branching on scalar records.
 */
@Service
@RequiredArgsConstructor
public class TaskFormOperationsImpl implements TaskFormOperations {

    private final TaskFormDataService taskFormDataService;
    private final FormResolver formResolver;
    private final BpmnService bpmnService;
    private final DBService dbService;
    private final FormAccessSupport formAccessSupport;

    @Override
    public TaskFormDTO getUserTaskForm(UUID id) {
        TaskFormDataService.UserTaskFormData data = taskFormDataService.loadUserTaskFormData(id);
        // WO-ACL-1: task form of a concrete instance = runtime read (variables prefill)
        formAccessSupport.requireRuntimePdAccess(data.processDefinitionId());
        // WO-C8-22: a linked formId wins over formKey (docs list the reference kinds as
        // mutually exclusive; the specific linked id beats the generic key on invalid
        // models carrying both). formKey/externalReference paths below are untouched.
        if (data.formId() != null && !data.formId().isBlank()) {
            // WO-C8-23: bindingType="deployment" pins the form version deployed together
            // with this instance's process version; anything else (latest/absent) keeps
            // the C8-22 path byte-identical.
            if ("deployment".equals(data.bindingType())) {
                UUID deploymentId = taskFormDataService.findDeploymentId(data.processDefinitionId());
                return formResolver.resolveTaskFormByFormIdAndDeployment(
                    data.formId(), deploymentId, prefillData(data.processInstanceId()));
            }
            // WO-C8-31: bindingType="versionTag" pins the latest form version carrying
            // the tag from this element's formDefinition (WO-C8-27: top-level .form JSON
            // field). The tag value is static per element — read from the cached model
            // of THIS instance's process version (no new row column, C8-26 pattern);
            // absent tag → explicit 404 from the resolver, never silent latest.
            if ("versionTag".equals(data.bindingType())) {
                return formResolver.resolveTaskFormByFormIdAndVersionTag(
                    data.formId(),
                    userTaskVersionTag(data.processDefinitionId(), data.bpmnElementId()),
                    prefillData(data.processInstanceId()));
            }
            return formResolver.resolveTaskFormByFormId(data.formId(), prefillData(data.processInstanceId()));
        }
        return resolveForm(data.formKey(), data.processInstanceId());
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
        TaskFormDataService.StartFormData data = taskFormDataService.loadStartFormData(key);

        formAccessSupport.requirePdAccess(data.definitionId());

        // WO-C8-26: linked formDefinition of the plain start event wins over every
        // legacy path (docs: the Modeler offers one Form type at a time — linked XOR
        // embedded XOR custom — so the structured reference beats the hand-written
        // zeebe:properties formKey on invalid models carrying both). Read from the
        // cached PD model (BpmnService cache; version-correct: this version's id);
        // the deployment pin uses this version's deployment_id.
        // ADR-6 §D7 bindings and scalar startFormKey below are byte-identical fallbacks.
        BpmnProcessDefinitionModel startModel =
            bpmnService.getProcessDefinitionModelById(data.definitionId());
        String startFormId = startModel.getStartFormId();
        if (startFormId != null && !startFormId.isBlank()) {
            if ("deployment".equals(startModel.getStartFormBindingType())) {
                return formResolver.resolveTaskFormByFormIdAndDeployment(
                    startFormId, data.deploymentId(), null);
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
        List<TaskFormDataService.BoundFormRef> bindings =
            taskFormDataService.findStartBindingRefs(data.definitionId());
        if (!bindings.isEmpty()) {
            // For now, return the first binding's artifact (start event binding)
            TaskFormDataService.BoundFormRef binding = bindings.get(0);
            return resolveByBinding(binding);
        }

        // Fallback to scalar startFormKey (back-compat)
        return resolveStartForm(data.startFormKey());
    }

    private TaskFormDTO resolveForm(String formKey, UUID processInstanceId) {
        return formResolver.resolveTaskForm(formKey, prefillData(processInstanceId));
    }

    private TaskFormDTO resolveStartForm(String startFormKey) {
        return formResolver.resolveTaskForm(startFormKey, null);
    }

    private TaskFormDTO resolveByBinding(TaskFormDataService.BoundFormRef binding) {
        // ADR-6 §D8: pin to artifact_version from binding
        TaskFormDataService.FormData form =
            taskFormDataService.findFormData(binding.artifactKey(), binding.artifactVersion());
        if (form == null) {
            // Fallback: try latest version
            return resolveStartForm(binding.artifactKey());
        }
        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setKind(form.kind());
        dto.setSchema(form.schemaJson());
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
