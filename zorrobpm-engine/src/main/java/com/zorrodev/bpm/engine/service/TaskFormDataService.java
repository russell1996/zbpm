package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-7 S2: JPA-backed form-data access, moved verbatim out of the REST-layer
 * {@code TaskFormOperationsImpl} (which stays behind as a thin facade over this
 * bean + {@code FormResolver}/{@code BpmnService}/{@code DBService}). The web
 * layer must not import {@code engine.repository.*}/{@code engine.entity.*};
 * everything JPA lives here, crossing the boundary only as nested records of
 * scalar values. No {@code @Transactional} — same as the original location,
 * all reads are single-row lookups.
 */
@Component
@RequiredArgsConstructor
public class TaskFormDataService {

    /** Scalar snapshot needed to resolve a concrete instance's task form. */
    public record UserTaskFormData(UUID taskId, String formId, String bindingType, String formKey,
            UUID processInstanceId, String bpmnElementId, UUID processDefinitionId) {
    }

    /** Scalar snapshot needed to resolve a start form by process key. */
    public record StartFormData(UUID definitionId, String key, UUID deploymentId, String startFormKey) {
    }

    /** Element-artifact binding reference (key + pinned version). */
    public record BoundFormRef(String artifactKey, Integer artifactVersion) {
    }

    /** Form body data (kind already mapped to its name, null-safe). */
    public record FormData(String kind, String schemaJson) {
    }

    private final UserTaskRepository userTaskRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ElementArtifactBindingRepository bindingRepository;
    private final FormRepository formRepository;

    public UserTaskFormData loadUserTaskFormData(UUID id) {
        UserTaskEntity task = userTaskRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found"));
        ProcessInstanceEntity pi = processInstanceRepository.findById(task.getProcessInstanceId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        return new UserTaskFormData(id, task.getFormId(), task.getBindingType(), task.getFormKey(),
            task.getProcessInstanceId(), task.getBpmnElementId(), pi.getProcessDefinitionId());
    }

    /**
     * Deployment pin for bindingType="deployment" — loaded lazily, only inside
     * that branch (same query timing as the original inline code).
     */
    public UUID findDeploymentId(UUID processDefinitionId) {
        return processDefinitionRepository.findById(processDefinitionId)
            .map(ProcessDefinitionEntity::getDeploymentId)
            .orElse(null);
    }

    public StartFormData loadStartFormData(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        return new StartFormData(pd.getId(), pd.getKey(), pd.getDeploymentId(), pd.getStartFormKey());
    }

    public List<BoundFormRef> findStartBindingRefs(UUID processDefinitionId) {
        return bindingRepository.findByProcessDefinitionId(processDefinitionId).stream()
            .map(b -> new BoundFormRef(b.getArtifactKey(), b.getArtifactVersion()))
            .toList();
    }

    public FormData findFormData(String artifactKey, Integer artifactVersion) {
        FormEntity form = formRepository.findByFormKeyAndVersion(artifactKey, artifactVersion)
            .orElse(null);
        if (form == null) {
            return null;
        }
        return new FormData(form.getKind() != null ? form.getKind().name() : null, form.getSchemaJson());
    }
}
