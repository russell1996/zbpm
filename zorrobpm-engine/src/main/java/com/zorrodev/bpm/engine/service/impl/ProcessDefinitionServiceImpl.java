package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessDefinitionServiceImpl implements ProcessDefinitionService {

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final BpmnParseService bpmnParseService;
    private final FileService fileService;
    private final com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository bindingRepository;
    private final TransactionTemplate transactionTemplate;
    private final ProcessDefinitionVersioning versioning;
    private final DeploymentArtifactRegistrar artifactRegistrar;
    private final DeploymentPostCommitActions postCommitActions;

    @Override
    public Optional<ProcessDefinition> getProcessDefinitionById(UUID id) {
        Optional<ProcessDefinitionEntity> processDefinitionEntityOptional = processDefinitionRepository.findById(id);

        return processDefinitionEntityOptional.map(this::fromEntity);
    }

    @SneakyThrows
    @Override
    public ProcessDefinition addProcessDefinition(String bpmn) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String sha256 = Base64.getEncoder().encodeToString(digest.digest(bpmn.getBytes(StandardCharsets.UTF_8)));

        BpmnProcessDefinitionModel model = bpmnParseService.parse(bpmn);
        String key = model.getKey();
        String name = model.getName();

        // WO-REL-18: reject deployment if any SERVICE_TASK lacks a resolvable job.
        // JAXB silently ignores unknown namespaces (e.g. flowable:* instead of zeebe:taskDefinition),
        // so a BPMN from a third-party tool passes XML parsing but will NPE at runtime.
        List<String> missingJobIds = model.getElements().stream()
            .filter(e -> e.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.SERVICE_TASK)
            .filter(e -> {
                String job = Optional.ofNullable(e.getExtensions())
                    .map(com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel::getServiceTaskExtension)
                    .map(com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel::getJob)
                    .orElse(null);
                return job == null || job.isBlank();
            })
            .map(com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel::getId)
            .toList();
        if (!missingJobIds.isEmpty()) {
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "SERVICE_TASK_MISSING_JOB",
                "Service task(s) without a job definition: " + missingJobIds
                    + " — add zeebe:taskDefinition to each service task, or remove them.",
                java.util.Map.of("elementIds", missingJobIds));
        }

        // WO-REL-15 (R-05): ALL database artifacts of a deployment (version row, bpmn model/file,
        // message/signal start subscriptions, timer start jobs, element bindings) are created
        // inside ONE transaction — either everything commits or nothing does. A failure between
        // version creation and artifact creation can no longer leave a "half-deployed" version.
        // The in-memory model cache is filled only in afterCommit (TransactionSynchronization),
        // never inside the tx, so the cache cannot contain a model that is not in the database.
        return transactionTemplate.execute(status -> {
            Optional<ProcessDefinitionEntity> processDefinitionEntityOptional = processDefinitionRepository.findBySha256(sha256);

            ProcessDefinitionEntity processDefinitionEntity;

            if (processDefinitionEntityOptional.isEmpty()) {
                UUID id = UUID.randomUUID();

                processDefinitionEntity = versioning.createNewVersionEntity(key, name, sha256, id, model.getStartFormKey(), model.getVersionTag());
                processDefinitionEntity.setDeploymentState(ProcessDefinitionEntity.STATE_PENDING);
                processDefinitionRepository.save(processDefinitionEntity);

                fileService.saveFile(id, bpmn);

                artifactRegistrar.registerMessageStartSubscriptions(key, id, model);
                artifactRegistrar.registerTimerStartJobs(key, id, model);
                artifactRegistrar.registerSignalStartSubscriptions(key, id, model);
                artifactRegistrar.registerUserTaskForms(id, model);

                // WO-VM-9a: carry-forward element_artifact_bindings from previous version
                if (processDefinitionEntity.getVersion() > 1) {
                    artifactRegistrar.carryForwardBindings(key, processDefinitionEntity.getVersion() - 1, processDefinitionEntity);
                }

                processDefinitionEntity.setDeploymentState(ProcessDefinitionEntity.STATE_ACTIVE);
                processDefinitionRepository.save(processDefinitionEntity);

                postCommitActions.cacheModelAfterCommit(id, model);
                postCommitActions.requestJobQueuesAfterCommit(model);
            } else {
                processDefinitionEntity = processDefinitionEntityOptional.get();
                // WO-REL-15: idempotent repair — a redeploy of the same sha256 whose previous attempt
                // left a non-ACTIVE deployment (crash between version commit and artifact creation,
                // or an explicitly FAILED attempt) re-assembles the missing artifacts instead of
                // bailing out with "already exists". ACTIVE deployments stay untouched (fast path).
                if (!ProcessDefinitionEntity.STATE_ACTIVE.equals(processDefinitionEntity.getDeploymentState())) {
                    log.warn("WO-REL-15: repairing incomplete deployment of {} (version {}, state {})",
                        processDefinitionEntity.getKey(), processDefinitionEntity.getVersion(),
                        processDefinitionEntity.getDeploymentState());
                    repairDeployment(processDefinitionEntity, model, bpmn);
                }
            }

            return fromEntity(processDefinitionEntity);
        });
    }

    /**
     * WO-REL-15: re-assembles every artifact of a deployment whose state is not ACTIVE
     * (crash between version commit and artifact creation, or explicit FAILED). Runs inside
     * the deployment transaction; idempotent — re-creating a model row / subscriptions /
     * jobs that already exist is a plain upsert or delete+insert. Element bindings are
     * re-carried from the previous version (leftovers of a failed attempt are dropped first).
     */
    private void repairDeployment(ProcessDefinitionEntity entity, BpmnProcessDefinitionModel model, String bpmn) {
        UUID id = entity.getId();
        fileService.saveFile(id, bpmn);
        artifactRegistrar.registerMessageStartSubscriptions(entity.getKey(), id, model);
        artifactRegistrar.registerTimerStartJobs(entity.getKey(), id, model);
        artifactRegistrar.registerSignalStartSubscriptions(entity.getKey(), id, model);
        artifactRegistrar.registerUserTaskForms(id, model);

        bindingRepository.findByProcessDefinitionId(id).forEach(bindingRepository::delete);
        if (entity.getVersion() != null && entity.getVersion() > 1) {
            artifactRegistrar.carryForwardBindings(entity.getKey(), entity.getVersion() - 1, entity);
        }

        entity.setDeploymentState(ProcessDefinitionEntity.STATE_ACTIVE);
        processDefinitionRepository.save(entity);

        postCommitActions.cacheModelAfterCommit(id, model);
        postCommitActions.requestJobQueuesAfterCommit(model);
    }

    /**
     * Builds Sort from the query parameters order field.
     * Default: ascending. When order=desc → descending on (name, version).
     */
    private Sort buildSort(ProcessDefinitionsQueryParameters parameters) {
        if (parameters.getOrder() != null && "desc".equalsIgnoreCase(parameters.getOrder())) {
            return Sort.by("name", "version").descending();
        }
        return Sort.by("name", "version").ascending();
    }

    @Override
    public PagedDataDTO<ProcessDefinition> getProcessDefinitions(ProcessDefinitionsQueryParameters parameters) {
        int maxPageSize = 200; // WO-A-05: clamp
        PageRequest pageRequest = PageRequest.of(
            Math.max(0, parameters.getPageIndex()),
            Math.min(maxPageSize, Math.max(1, parameters.getPageSize())),
            buildSort(parameters));

        List<Specification<ProcessDefinitionEntity>> specifications = new LinkedList<>();
        if (parameters.getName() != null && !parameters.getName().isBlank()) {
            specifications.add(ProcessDefinitionRepository.byNameContains(parameters.getName()));
        }
        if (parameters.getProcessDefinitionKey() != null) {
            specifications.add(ProcessDefinitionRepository.byKey(parameters.getProcessDefinitionKey()));
        }
        if (parameters.getProcessDefinitionVersion() != null) {
            specifications.add(ProcessDefinitionRepository.byVersion(parameters.getProcessDefinitionVersion()));
        }
        if (Boolean.TRUE.equals(parameters.getLatestVersionOnly())) {
            specifications.add(ProcessDefinitionRepository.latestVersion());
        }

        Page<ProcessDefinitionEntity> page =
            processDefinitionRepository.findAll(Specification.allOf(specifications), pageRequest);

        PagedDataDTO<ProcessDefinition> data = new PagedDataDTO<>();
        data.setPageIndex(parameters.getPageIndex());
        data.setPageSize(parameters.getPageSize());
        data.setTotalElements(page.getTotalElements());
        data.setData(page.getContent().stream().map(this::fromEntity).toList());

        return data;
    }

    @Override
    public PagedDataDTO<ProcessDefinition> getProcessDefinitions(ProcessDefinitionsQueryParameters parameters, Collection<UUID> allowedPdIds) {
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            PagedDataDTO<ProcessDefinition> empty = new PagedDataDTO<>();
            empty.setPageIndex(parameters.getPageIndex());
            empty.setPageSize(parameters.getPageSize());
            empty.setTotalElements(0L);
            empty.setData(List.of());
            return empty;
        }
        if (allowedPdIds != null) {
            int maxPageSize = 200;
            PageRequest pageRequest = PageRequest.of(
                Math.max(0, parameters.getPageIndex()),
                Math.min(maxPageSize, Math.max(1, parameters.getPageSize())),
                buildSort(parameters));

            java.util.List<Specification<ProcessDefinitionEntity>> specs = new java.util.LinkedList<>();
            specs.add((root, q, cb) -> root.get("id").in(allowedPdIds));
            if (parameters.getName() != null && !parameters.getName().isBlank()) {
                specs.add(ProcessDefinitionRepository.byNameContains(parameters.getName()));
            }
            if (parameters.getProcessDefinitionKey() != null) {
                specs.add(ProcessDefinitionRepository.byKey(parameters.getProcessDefinitionKey()));
            }
            if (parameters.getProcessDefinitionVersion() != null) {
                specs.add(ProcessDefinitionRepository.byVersion(parameters.getProcessDefinitionVersion()));
            }
            if (Boolean.TRUE.equals(parameters.getLatestVersionOnly())) {
                specs.add(ProcessDefinitionRepository.latestVersion());
            }

            Page<ProcessDefinitionEntity> page =
                processDefinitionRepository.findAll(Specification.allOf(specs), pageRequest);

            PagedDataDTO<ProcessDefinition> data = new PagedDataDTO<>();
            data.setPageIndex(parameters.getPageIndex());
            data.setPageSize(parameters.getPageSize());
            data.setTotalElements(page.getTotalElements());
            data.setData(page.getContent().stream().map(this::fromEntity).toList());
            return data;
        }
        return getProcessDefinitions(parameters);
    }

    private ProcessDefinition fromEntity(ProcessDefinitionEntity processDefinitionEntity) {
        ProcessDefinition processDefinition = new ProcessDefinition();
        processDefinition.setId(processDefinitionEntity.getId());
        processDefinition.setKey(processDefinitionEntity.getKey());
        processDefinition.setVersion(processDefinitionEntity.getVersion());
        processDefinition.setName(processDefinitionEntity.getName());
        processDefinition.setSha256(processDefinitionEntity.getSha256());
        processDefinition.setCreatedAt(processDefinitionEntity.getCreatedAt());
        processDefinition.setStartFormKey(processDefinitionEntity.getStartFormKey());
        return processDefinition;
    }

}
