package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Enqueues service tasks via the transactional outbox pattern.
 * Instead of publishing events after commit (which can lose messages when MQ is down),
 * we INSERT into the outbox table within the current transaction.
 * A separate OutboxPollerService publishes entries to MQ.
 */
@Slf4j
@Profile("!test")
@Service
@RequiredArgsConstructor
public class ServiceTaskEnqueueServiceImpl implements ServiceTaskEnqueueService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    @Override
    public void enqueueAfterCommit(UUID serviceTaskId) {
        Activity activity = dbService.getActivity(serviceTaskId);
        ProcessInstance pi = dbService.getProcessInstance(activity.getProcessInstanceId());
        UUID processDefinitionId = pi.getProcessDefinitionId();
        UUID processInstanceId = activity.getProcessInstanceId();
        String bpmnElementId = activity.getBpmnElementId();

        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnElementModel element = bpmn.getElement(bpmnElementId);
        String job = element.getExtensions().getServiceTaskExtension().getJob();

        Map<String, ProcessVariable> variables = dbService.getVariables(processInstanceId, serviceTaskId).stream()
            .collect(Collectors.toMap(com.zorrodev.bpm.contract.model.ProcessVariable::getName, pv -> {
                ProcessVariable v = new ProcessVariable();
                v.setName(pv.getName());
                v.setValue(pv.getValue());
                v.setType(pv.getType().toString());
                return v;
            }));

        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(serviceTaskId);
        detail.setProcessDefinitionId(processDefinitionId);
        detail.setProcessInstanceId(processInstanceId);
        detail.setServiceTaskKey(bpmnElementId);
        detail.setJob(job);
        detail.setVariables(variables);

        try {
            OutboxEntry entry = new OutboxEntry();
            entry.setId(UUID.randomUUID());
            // WO-REL-12 R-01: producer knows the type — no payload guessing downstream
            entry.setKind(com.zorrodev.bpm.engine.entity.OutboxKind.SERVICE_TASK);
            entry.setPayload(objectMapper.writeValueAsString(detail));
            entry.setCreatedAt(Instant.now());
            entry.setPublished(false);
            outboxRepository.save(entry);
            log.info("Enqueued service task {} to outbox", serviceTaskId);
        } catch (Exception e) {
            log.error("Failed to serialize service task {} for outbox", serviceTaskId, e);
            throw new RuntimeException("Failed to enqueue service task", e);
        }
    }
}
