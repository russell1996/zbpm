package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-DEBT-1h: домен ServiceTasks — реализация.
 * Перенесено 1:1 из DBServiceImpl (5 методов).
 */
@Service
@RequiredArgsConstructor
public class ServiceTaskDbOperationsImpl implements ServiceTaskDbOperations {

    private final ServiceTaskRepository serviceTaskRepository;
    private final ActivityRepository activityRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final DomainEventEmitter domainEventEmitter;

    @Override
    public void createServiceTask(UUID activityId) {
        createServiceTask(activityId, 3, null);
    }

    @Override
    public void createServiceTask(UUID activityId, int retriesRemaining, String job) {
        createServiceTask(activityId, retriesRemaining, job, null);
    }

    @Override
    public void createServiceTask(UUID activityId, int retriesRemaining, String job, Integer pendingListenerIndex) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        ServiceTaskEntity entity = new ServiceTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());
        entity.setRetriesRemaining(retriesRemaining);
        entity.setJob(job);
        entity.setPendingListenerIndex(pendingListenerIndex);

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        serviceTaskRepository.save(entity);
        domainEventEmitter.emitServiceTaskCreated(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), activityId, job);
    }

    @Override
    public int decrementServiceTaskRetries(UUID serviceTaskId) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        int remaining = (entity.getRetriesRemaining() == null ? 1 : entity.getRetriesRemaining()) - 1;
        entity.setRetriesRemaining(remaining);
        serviceTaskRepository.save(entity);
        return remaining;
    }

    @Override
    public void setServiceTaskRetries(UUID serviceTaskId, int retries) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        entity.setRetriesRemaining(retries);
        serviceTaskRepository.save(entity);
    }

    @Override
    public void setPendingListenerIndex(UUID serviceTaskId, Integer pendingListenerIndex) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        entity.setPendingListenerIndex(pendingListenerIndex);
        serviceTaskRepository.save(entity);
    }

    @Override
    public Integer getPendingListenerIndex(UUID serviceTaskId) {
        return serviceTaskRepository.findById(serviceTaskId).orElseThrow().getPendingListenerIndex();
    }

    @Override
    public void setPendingEndListenerIndex(UUID serviceTaskId, Integer pendingEndListenerIndex) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        entity.setPendingEndListenerIndex(pendingEndListenerIndex);
        serviceTaskRepository.save(entity);
    }

    @Override
    public Integer getPendingEndListenerIndex(UUID serviceTaskId) {
        return serviceTaskRepository.findById(serviceTaskId).orElseThrow().getPendingEndListenerIndex();
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId) {
        serviceTaskRepository.setCompletedAt(serviceTaskId, Instant.now());
    }
}
