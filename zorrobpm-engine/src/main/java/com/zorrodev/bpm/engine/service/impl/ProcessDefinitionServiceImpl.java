package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProcessDefinitionServiceImpl implements ProcessDefinitionService {

    private static final Object VERSION_LOCK = new Object();

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final BpmnService bpmnService;
    private final BpmnParseService bpmnParseService;
    private final FileService fileService;
    private final DBService dbService;

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

        Optional<ProcessDefinitionEntity> processDefinitionEntityOptional = processDefinitionRepository.findBySha256(sha256);

        ProcessDefinitionEntity processDefinitionEntity;

        if (processDefinitionEntityOptional.isEmpty()) {
            UUID id = UUID.randomUUID();

            synchronized (VERSION_LOCK) {
                Integer maxVersion = processDefinitionRepository.findMaxByKey(key).orElse(0);
                processDefinitionEntity = new ProcessDefinitionEntity();
                processDefinitionEntity.setId(id);
                processDefinitionEntity.setKey(key);
                processDefinitionEntity.setName(name);
                processDefinitionEntity.setVersion(maxVersion + 1);
                processDefinitionEntity.setSha256(sha256);
                processDefinitionEntity.setCreatedAt(Instant.now());
                processDefinitionEntity.setStartFormKey(model.getStartFormKey());
                processDefinitionEntity = processDefinitionRepository.save(processDefinitionEntity);
            }

            bpmnService.addProcessDefinition(id, model);
            fileService.saveFile(id, bpmn);

            registerMessageStartSubscriptions(key, id, model);
            registerTimerStartJobs(key, id, model);
            registerSignalStartSubscriptions(key, id, model);
        } else {
            processDefinitionEntity = processDefinitionEntityOptional.get();
        }

        return fromEntity(processDefinitionEntity);
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
            Instant dueAt = switch (timer.getType()) {
                case DURATION -> Instant.now().plus(java.time.Duration.parse(timer.getExpression()));
                case DATE -> Instant.parse(timer.getExpression());
                case CYCLE -> com.zorrodev.bpm.engine.scheduler.TimerExpressions.firstOccurrence(timer.getExpression(), Instant.now());
            };
            dbService.createTimerStartJob(key, processDefinitionId, start.getId(), dueAt);
        }
    }

    @Override
    public PagedDataDTO<ProcessDefinition> getProcessDefinitions(ProcessDefinitionsQueryParameters parameters) {
        PageRequest pageRequest = PageRequest.of(parameters.getPageIndex(), parameters.getPageSize(), Sort.by("name", "version").ascending());

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
