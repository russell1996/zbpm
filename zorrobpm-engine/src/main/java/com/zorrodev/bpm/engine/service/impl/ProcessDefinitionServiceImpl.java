package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.exchange.JobQueuesRequested;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DatabaseMetaData;
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
    private final BpmnService bpmnService;
    private final BpmnParseService bpmnParseService;
    private final FileService fileService;
    private final DBService dbService;
    private final com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository bindingRepository;
    private final com.zorrodev.bpm.engine.repository.FormRepository formRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;
    private final ElementSupport elementSupport;
    private final ApplicationEventPublisher eventPublisher;

    /** Cached database product name — detected once on first use. */
    private volatile String databaseProduct;

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

                processDefinitionEntity = createNewVersionEntity(key, name, sha256, id, model.getStartFormKey());
                processDefinitionEntity.setDeploymentState(ProcessDefinitionEntity.STATE_PENDING);
                processDefinitionRepository.save(processDefinitionEntity);

                fileService.saveFile(id, bpmn);

                registerMessageStartSubscriptions(key, id, model);
                registerTimerStartJobs(key, id, model);
                registerSignalStartSubscriptions(key, id, model);

                // WO-VM-9a: carry-forward element_artifact_bindings from previous version
                if (processDefinitionEntity.getVersion() > 1) {
                    carryForwardBindings(key, processDefinitionEntity.getVersion() - 1, processDefinitionEntity);
                }

                processDefinitionEntity.setDeploymentState(ProcessDefinitionEntity.STATE_ACTIVE);
                processDefinitionRepository.save(processDefinitionEntity);

                cacheModelAfterCommit(id, model);
                requestJobQueuesAfterCommit(model);
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
     * WO-ARCH-2 + WO-A-03: Creates a new process definition version inside a transaction
     * protected by pg_advisory_xact_lock(key.hashCode()).
     * Uses TransactionTemplate (not @Transactional) to avoid self-invocation proxy bypass.
     * Lock auto-released on commit/rollback. Different keys → different locks.
     *
     * WO-A-03 FAIL-CLOSED: PG advisory lock failure propagates (rollback),
     * no broad catch. H2: lock is skipped (function not supported).
     *
     * Kept as the package-private entry point for the WO-A-03 fail-closed unit test;
     * the production deployment path (addProcessDefinition) runs createNewVersionEntity
     * inside its own single transaction instead (WO-REL-15).
     */
    ProcessDefinitionEntity createNewVersionWithAdvisoryLock(
            String key, String name, String sha256, UUID id, String startFormKey) {
        return transactionTemplate.execute(status -> {
            ProcessDefinitionEntity entity = createNewVersionEntity(key, name, sha256, id, startFormKey);
            entity.setDeploymentState(ProcessDefinitionEntity.STATE_ACTIVE);
            return processDefinitionRepository.save(entity);
        });
    }

    /**
     * WO-REL-15: builds a new version entity (advisory lock + next version number) WITHOUT
     * saving it — the caller persists it inside the deployment transaction. The advisory lock
     * is acquired on the caller's connection and released at that transaction's commit/rollback.
     */
    private ProcessDefinitionEntity createNewVersionEntity(
            String key, String name, String sha256, UUID id, String startFormKey) {
        // WO-A-03: acquire advisory lock based on database dialect
        acquireAdvisoryLock(key);
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key).orElse(0);
        ProcessDefinitionEntity entity = new ProcessDefinitionEntity();
        entity.setId(id);
        entity.setKey(key);
        entity.setName(name);
        entity.setVersion(maxVersion + 1);
        entity.setSha256(sha256);
        entity.setCreatedAt(Instant.now());
        entity.setStartFormKey(startFormKey);
        return entity;
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
        registerMessageStartSubscriptions(entity.getKey(), id, model);
        registerTimerStartJobs(entity.getKey(), id, model);
        registerSignalStartSubscriptions(entity.getKey(), id, model);

        bindingRepository.findByProcessDefinitionId(id).forEach(bindingRepository::delete);
        if (entity.getVersion() != null && entity.getVersion() > 1) {
            carryForwardBindings(entity.getKey(), entity.getVersion() - 1, entity);
        }

        entity.setDeploymentState(ProcessDefinitionEntity.STATE_ACTIVE);
        processDefinitionRepository.save(entity);

        cacheModelAfterCommit(id, model);
        requestJobQueuesAfterCommit(model);
    }

    /**
     * WO-REL-15: fills the in-memory model cache only after the surrounding deployment
     * transaction has COMMITTED — never inside the tx (a rolled-back deployment must not
     * leave a cached model that does not exist in the database). registerSynchronization
     * defers the cache put to afterCommit, which is not invoked on rollback. Without an
     * active transaction synchronization (e.g. unit tests running the TransactionTemplate
     * callback directly) the cache is filled immediately.
     */
    private void cacheModelAfterCommit(UUID processDefinitionId, BpmnProcessDefinitionModel model) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bpmnService.addProcessDefinition(processDefinitionId, model);
                }
            });
        } else {
            bpmnService.addProcessDefinition(processDefinitionId, model);
        }
    }

    /**
     * WO-REL-16: announces this definition's job types so the messaging layer can declare their
     * queues now, instead of lazily on the first message actually sent to them (a job type that
     * had never run yet simply had no queue on the broker).
     *
     * <p>Deferred to afterCommit for the same reason as {@link #cacheModelAfterCommit} — a
     * rolled-back deployment must not announce queues for a definition that does not exist — and
     * so that a broker problem cannot fail the deployment transaction. Publishing is best-effort:
     * the listener is expected to swallow its own broker errors, but the try/catch here guarantees
     * that even a listener that throws cannot break a deployment that has already committed. The
     * lazy declare on first send stays as the fallback.
     */
    private void requestJobQueuesAfterCommit(BpmnProcessDefinitionModel model) {
        java.util.Set<String> jobTypes = model.getJobTypes();
        if (jobTypes.isEmpty()) return;

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishJobQueuesRequested(jobTypes);
                }
            });
        } else {
            publishJobQueuesRequested(jobTypes);
        }
    }

    private void publishJobQueuesRequested(java.util.Set<String> jobTypes) {
        try {
            eventPublisher.publishEvent(new JobQueuesRequested(jobTypes));
        } catch (Exception e) {
            log.warn("WO-REL-16: could not announce job queues {} — they will be declared lazily "
                + "on the first message instead", jobTypes, e);
        }
    }

    /**
     * WO-A-03: DB-dialect-aware advisory lock.
     * - PG: execute pg_advisory_xact_lock; ANY exception propagates (fail-closed, rollback).
     * - H2: skip (function not supported), log once.
     */
    private void acquireAdvisoryLock(String key) {
        String product = getDatabaseProduct();
        if ("PostgreSQL".equals(product)) {
            long lockKey = key.hashCode();
            jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) conn -> {
                try (var ps = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    ps.setLong(1, lockKey);
                    ps.execute();
                }
                return null;
            });
            // WO-A-03: no catch — any exception propagates and rolls back the transaction
        } else {
            log.debug("Advisory lock skipped for database product: {}", product);
        }
    }

    /** Detect and cache database product name once. */
    private String getDatabaseProduct() {
        if (databaseProduct == null) {
            try {
                databaseProduct = dataSource.getConnection().getMetaData().getDatabaseProductName();
            } catch (Exception e) {
                log.warn("Could not detect database product, assuming PostgreSQL", e);
                databaseProduct = "PostgreSQL";
            }
        }
        return databaseProduct;
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

    /**
     * Registers (and supersedes prior versions of) message start subscriptions for the deployed
     * definition, so a correlated message of that name starts a new instance of the latest version.
     */
    private void registerMessageStartSubscriptions(String key, UUID processDefinitionId, BpmnProcessDefinitionModel model) {
        var messageStarts = model.getMessageStartEvents();
        if (messageStarts.isEmpty()) {
            return;
        }
        dbService.deleteMessageStartSubscriptionsByKey(key);
        for (BpmnElementModel start : messageStarts) {
            String messageName = Optional.ofNullable(start.getExtensions())
                .map(BpmnElementExtensionModel::getMessageEventExtension)
                .map(com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel::getMessageName)
                .orElse(null);
            if (messageName != null) {
                dbService.createMessageStartSubscription(key, processDefinitionId, start.getId(), messageName);
            }
        }
    }

    /**
     * Registers (and supersedes prior versions of) signal start subscriptions for the deployed
     * definition, so a broadcast signal of that name starts a new instance of the latest version.
     */
    private void registerSignalStartSubscriptions(String key, UUID processDefinitionId, BpmnProcessDefinitionModel model) {
        var signalStarts = model.getSignalStartEvents();
        if (signalStarts.isEmpty()) {
            return;
        }
        dbService.deleteSignalStartSubscriptionsByKey(key);
        for (BpmnElementModel start : signalStarts) {
            String signalName = Optional.ofNullable(start.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel::getName)
                .orElse(null);
            if (signalName != null) {
                dbService.createSignalStartSubscription(key, processDefinitionId, start.getId(), signalName);
            }
        }
    }

    /**
     * Registers (and supersedes prior versions of) timer start jobs for the deployed definition.
     * Duration timers are due relative to deploy time; date timers at the given instant.
     */
    private void registerTimerStartJobs(String key, UUID processDefinitionId, BpmnProcessDefinitionModel model) {
        var timerStarts = model.getTimerStartEvents();
        if (timerStarts.isEmpty()) {
            return;
        }
        dbService.deleteTimerStartJobsByKey(key);
        for (BpmnElementModel start : timerStarts) {
            com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel timer = Optional.ofNullable(start.getExtensions())
                .map(BpmnElementExtensionModel::getTimerEventExtension)
                .orElse(null);
            if (timer == null || timer.getType() == null || timer.getExpression() == null) {
                continue;
            }
            // WO-ENG-4: delegate to ElementSupport which uses businessZone (not ZoneId.systemDefault())
            // for CYCLE, and provides a single source of truth for all timer literal parsing.
            // This also eliminates the code-clone switch that duplicated ElementSupport.computeDueAtFallback.
            Instant dueAt = elementSupport.computeDueAt(timer, start.getId());
            // WO-REL-14 (R-04, defect 2): persist the initial remaining-repetitions count for a
            // bounded cycle (R<n>/...) so TimerStartJobExecutor can decrement the PERSISTED value
            // on each fire instead of recomputing repeatCount from the BPMN model every time
            // (which meant a bounded cycle never actually exhausted). Null = infinite/non-cycle.
            Integer remainingCount = timer.getType() == com.zorrodev.bpm.engine.bpmn.model.TimerEventType.CYCLE
                ? initialRemainingCount(timer.getExpression())
                : null;
            dbService.createTimerStartJob(key, processDefinitionId, start.getId(), dueAt, remainingCount);
        }
    }

    /** WO-REL-14: repeatCount - 1, or null for unbounded (R/...) / cron cycles. */
    private Integer initialRemainingCount(String cycleExpression) {
        int repeatCount = com.zorrodev.bpm.engine.scheduler.TimerExpressions.repeatCount(cycleExpression);
        return repeatCount > 0 ? repeatCount - 1 : null;
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

    /**
     * WO-VM-9a: Carry-forward element_artifact_bindings from previous PD version to new version.
     * Copies bindings by elementId and re-pins artifact_version to current artifact version.
     */
    private void carryForwardBindings(String key, int oldVersion, ProcessDefinitionEntity newPd) {
        var oldBindings = bindingRepository.findByKeyAndOldVersion(key, oldVersion);
        for (var oldBinding : oldBindings) {
            // Find current artifact version
            formRepository.findTopByFormKeyOrderByVersionDesc(oldBinding.getArtifactKey()).ifPresent(currentArtifact -> {
                var newBinding = new com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity();
                newBinding.setId(java.util.UUID.randomUUID());
                newBinding.setProcessDefinitionId(newPd.getId());
                newBinding.setProcessDefinitionVersion(newPd.getVersion());
                newBinding.setElementId(oldBinding.getElementId());
                newBinding.setArtifactKey(oldBinding.getArtifactKey());
                newBinding.setArtifactVersion(currentArtifact.getVersion());
                newBinding.setCreatedAt(java.time.Instant.now());
                bindingRepository.save(newBinding);
            });
        }
    }

}
