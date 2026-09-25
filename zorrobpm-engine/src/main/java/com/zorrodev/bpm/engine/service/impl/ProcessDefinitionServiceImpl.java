package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.AdvisoryDeployLock;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
    private final ProcessRepository processRepository;
    private final BpmnParseService bpmnParseService;
    private final FileService fileService;
    private final com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository bindingRepository;
    private final TransactionTemplate transactionTemplate;
    private final ProcessDefinitionVersioning versioning;
    private final DeploymentArtifactRegistrar artifactRegistrar;
    private final DeploymentPostCommitActions postCommitActions;
    private final AdvisoryDeployLock advisoryDeployLock;

    @Override
    public Optional<ProcessDefinition> getProcessDefinitionById(UUID id) {
        Optional<ProcessDefinitionEntity> processDefinitionEntityOptional = processDefinitionRepository.findById(id);

        return processDefinitionEntityOptional.map(this::fromEntity);
    }

    @SneakyThrows
    @Override
    public ProcessDefinition addProcessDefinition(String bpmn) {
        return addProcessDefinition(bpmn, null);
    }

    /**
     * WO-C8-18: same as {@link #addProcessDefinition(String)}, but stamps {@code deploymentId}
     * on newly created version rows (batch deploys). A null id keeps single-deploy behaviour
     * byte-identical (nullable column, no extra rows); the sha256 fast path never rewrites linkage.
     */
    @SneakyThrows
    @Override
    public ProcessDefinition addProcessDefinition(String bpmn, UUID deploymentId) {
        // WO-SEC-62: fail fast on oversized uploads BEFORE getBytes()+SHA256+parse —
        // covers direct service callers that bypass MVC @Size validation (e.g.
        // batch deploys). Same 413 BPMN_TOO_LARGE contract as the MVC path.
        if (bpmn != null && bpmn.length() > com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO.MAX_BPMN_LENGTH) {
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                "BPMN_TOO_LARGE",
                "BPMN XML exceeds the 5 MB upload limit",
                java.util.Map.of("maxLength",
                    com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO.MAX_BPMN_LENGTH,
                    "actualLength", bpmn.length()));
        }
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
            // WO-SCALE-4: serialize same-key deploys BEFORE the sha256 dedup check.
            // The lock used to live only inside createNewVersionEntity (i.e. AFTER the
            // check) — two racers with identical content both passed findBySha256, then
            // serialized on the lock, then the loser died on uk_process_definitions__sha256
            // (raw 500, soak 451/467). Now the check below re-reads UNDER the lock
            // (double-checked dedup): the loser sees the winner's row and returns it.
            // xact-scoped: joins the caller's tx (single deploy or batch item alike).
            advisoryDeployLock.acquireForKey(key);
            // WO-ENG-18: every deployed key owns exactly one process-registry row, no
            // matter which path deployed it (single, batch, version-upload,
            // submission-approve — all funnel through here). Unconditional on purpose:
            // it also heals orphaned keys on dedup-hit/redeploy (same sha) and in the
            // REL-15 repair path, not just on version creation. Runs under the key lock
            // above, inside this tx — a rolled-back deploy leaves no orphaned row.
            ensureProcessRow(key, name);
            Optional<ProcessDefinitionEntity> processDefinitionEntityOptional = processDefinitionRepository.findBySha256(sha256);

            ProcessDefinitionEntity processDefinitionEntity;

            if (processDefinitionEntityOptional.isEmpty()) {
                UUID id = UUID.randomUUID();

                processDefinitionEntity = versioning.createNewVersionEntity(key, name, sha256, id, model.getStartFormKey(), model.getVersionTag());
                processDefinitionEntity.setDeploymentState(ProcessDefinitionEntity.STATE_PENDING);
                processDefinitionEntity.setDeploymentId(deploymentId);
                // WO-ENG-17: Camunda-style historyTimeToLive с процесса (fail-fast 400 на мусор —
                // тот же контракт, что SERVICE_TASK_MISSING_JOB выше; отсутствует → NULL =
                // наследовать глобальный TTL; явный 0/отрицательный — reject, двусмысленности нет).
                processDefinitionEntity.setHistoryTimeToLiveDays(parseHistoryTimeToLive(model.getHistoryTimeToLive()));
                processDefinitionRepository.save(processDefinitionEntity);

                fileService.saveFile(id, bpmn);

                artifactRegistrar.registerMessageStartSubscriptions(key, id, model);
                artifactRegistrar.registerTimerStartJobs(key, id, model);
                artifactRegistrar.registerSignalStartSubscriptions(key, id, model);
                artifactRegistrar.registerUserTaskForms(id, model, processDefinitionEntity.getDeploymentId());

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
    /**
     * WO-ENG-17: парсинг {@code camunda:historyTimeToLive} в дни (fail-fast 400).
     * Отсутствует/пусто → NULL (наследовать глобальный TTL). Мусор или
     * не-положительное число → {@code INVALID_HISTORY_TTL} 400: молча глотать
     * означало бы «деплой принял TTL, но не применил» — худший исход для retention.
     */
    private Integer parseHistoryTimeToLive(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int days;
        try {
            days = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_HISTORY_TTL",
                "camunda:historyTimeToLive must be a positive integer number of days, got '" + raw + "'",
                java.util.Map.of("value", raw));
        }
        if (days <= 0) {
            throw new com.zorrodev.bpm.contract.exception.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_HISTORY_TTL",
                "camunda:historyTimeToLive must be a positive integer number of days, got '" + raw + "'",
                java.util.Map.of("value", raw));
        }
        return days;
    }

    private void repairDeployment(ProcessDefinitionEntity entity, BpmnProcessDefinitionModel model, String bpmn) {
        UUID id = entity.getId();
        // WO-ENG-17: repair тоже несёт TTL с модели (иначе чиненый PENDING-деплой
        // остался бы с NULL против значения в BPMN — то же поле, тот же парсинг).
        entity.setHistoryTimeToLiveDays(parseHistoryTimeToLive(model.getHistoryTimeToLive()));
        fileService.saveFile(id, bpmn);
        artifactRegistrar.registerMessageStartSubscriptions(entity.getKey(), id, model);
        artifactRegistrar.registerTimerStartJobs(entity.getKey(), id, model);
        artifactRegistrar.registerSignalStartSubscriptions(entity.getKey(), id, model);
        artifactRegistrar.registerUserTaskForms(id, model, entity.getDeploymentId());

        bindingRepository.deleteByProcessDefinitionId(id);
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
        // WO-ENG-9: archived keys hidden by default; includeArchived=true shows them
        if (!Boolean.TRUE.equals(parameters.getIncludeArchived())) {
            java.util.Set<String> archivedKeys = archivedKeys();
            if (!archivedKeys.isEmpty()) {
                specifications.add((root, q, cb) -> cb.not(root.get("key").in(archivedKeys)));
            }
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

    private java.util.Set<String> archivedKeys() {
        return processRepository.findByArchivedTrue().stream()
            .map(com.zorrodev.bpm.engine.entity.ProcessEntity::getDefinitionKey)
            .collect(java.util.stream.Collectors.toSet());
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
            if (!Boolean.TRUE.equals(parameters.getIncludeArchived())) {
                java.util.Set<String> archivedKeys = archivedKeys();
                if (!archivedKeys.isEmpty()) {
                    specs.add((root, q, cb) -> cb.not(root.get("key").in(archivedKeys)));
                }
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

    /**
     * WO-ENG-18: shared find-or-create for the {@code process}-registry row (see the
     * interface for the invariant). Race guard mirrors the
     * {@code ProcessSubmissionServiceImpl.submit} pattern: pre-check + save, and the
     * unique {@code definition_key} converts a concurrent first-deploy race into a
     * {@code DataIntegrityViolationException} — in that case the winner's row is
     * re-read, so two racers never leave two rows (and never 500).
     *
     * <p>WO-QW-4 (NEW-16b) — честная граница этого guard'а: catch срабатывает,
     * только если нарушение уникальности всплывает ЗДЕСЬ (flush до catch).
     * Внутри deploy-транзакции (`addProcessDefinition`, под key-lock) `save`
     * без flush откладывает нарушение на commit вызывающей транзакции — catch
     * не срабатывает, деплой откатывается целиком (REL-15, это и есть
     * «never 500»: откат, не raw-500). Живой случай catch'а — вне-lock вызов
     * из submission-approve, где чужой approve вставил строку между нашим
     * pre-check и save. REQUIRES_NEW + saveAndFlush сознательно НЕ введены:
     * они вынесли бы строку из deploy-транзакции и нарушили REL-15-инвариант
     * «откат деплоя не оставляет orphan-row».
     */
    @Override
    public ProcessEntity ensureProcessRow(String definitionKey, String name) {
        return processRepository.findByDefinitionKey(definitionKey)
            .orElseGet(() -> {
                ProcessEntity process = new ProcessEntity();
                process.setId(UUID.randomUUID());
                process.setDefinitionKey(definitionKey);
                process.setName(name != null ? name : definitionKey);
                process.setCreatedAt(Instant.now());
                try {
                    return processRepository.save(process);
                } catch (DataIntegrityViolationException e) {
                    log.debug("WO-ENG-18: concurrent process-row insert for key={}, using winner's row",
                        definitionKey);
                    return processRepository.findByDefinitionKey(definitionKey)
                        .orElseThrow(() -> e);
                }
            });
    }

    @Override
    public void archiveProcess(String key) {
        com.zorrodev.bpm.engine.entity.ProcessEntity process = processRepository.findByDefinitionKey(key)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Process not found: " + key));
        if (!process.isArchived()) {
            process.setArchived(true);
            process.setArchivedAt(java.time.Instant.now());
            processRepository.save(process);
        }
    }

    @Override
    public void unarchiveProcess(String key) {
        com.zorrodev.bpm.engine.entity.ProcessEntity process = processRepository.findByDefinitionKey(key)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Process not found: " + key));
        if (process.isArchived()) {
            process.setArchived(false);
            process.setArchivedAt(null);
            processRepository.save(process);
        }
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
        // WO-ENG-9: archived flag from registry (process table, one row per key)
        boolean archived = processRepository.findByDefinitionKey(processDefinitionEntity.getKey())
            .map(com.zorrodev.bpm.engine.entity.ProcessEntity::isArchived).orElse(false);
        processDefinition.setArchived(archived);
        return processDefinition;
    }

}
