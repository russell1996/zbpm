package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateType;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds user tasks straight through the repositories instead of running a process, so that
 * assignees, candidates and creation timestamps are exactly what a scenario needs.
 *
 * <p>The process definition is deployed for real - the user task mapper resolves element names
 * from the BPMN - but the process instance is inserted directly and never started, so the only
 * tasks that exist are the ones a test asks for. Tests isolate themselves by filtering on
 * {@link #processInstanceId()}.
 */
class UserTaskQueryFixture {

    private static final String BPMN_PATH = "src/test/files/test16.bpmn";
    /** A user task element that exists in the deployed definition. */
    static final String ELEMENT_ID = "approveTask";

    private final UserTaskRepository userTaskRepository;
    private final UserTaskCandidateRepository candidateRepository;
    private final UUID processDefinitionId;
    private final UUID processInstanceId;

    UserTaskQueryFixture(ProcessDefinitionService processDefinitionService,
                         ProcessInstanceRepository processInstanceRepository,
                         UserTaskRepository userTaskRepository,
                         UserTaskCandidateRepository candidateRepository) throws Exception {
        this.userTaskRepository = userTaskRepository;
        this.candidateRepository = candidateRepository;

        ProcessDefinition definition =
            processDefinitionService.addProcessDefinition(Files.readString(Paths.get(BPMN_PATH)));
        this.processDefinitionId = definition.getId();

        ProcessInstanceEntity instance = new ProcessInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setProcessDefinitionId(processDefinitionId);
        instance.setStartedAt(Instant.now());
        processInstanceRepository.save(instance);
        this.processInstanceId = instance.getId();
    }

    UUID processInstanceId() {
        return processInstanceId;
    }

    UUID task(String assignee, Instant createdAt, List<String> candidateUsers, List<String> candidateGroups) {
        UserTaskEntity task = new UserTaskEntity();
        task.setId(UUID.randomUUID());
        task.setBpmnElementId(ELEMENT_ID);
        task.setProcessInstanceId(processInstanceId);
        task.setProcessDefinitionId(processDefinitionId);
        task.setCreatedAt(createdAt);
        task.setAssignee(assignee);
        userTaskRepository.save(task);

        candidateUsers.forEach(value -> candidate(task.getId(), UserTaskCandidateType.USER, value));
        candidateGroups.forEach(value -> candidate(task.getId(), UserTaskCandidateType.GROUP, value));
        return task.getId();
    }

    UUID task(String assignee, List<String> candidateUsers, List<String> candidateGroups) {
        return task(assignee, Instant.now(), candidateUsers, candidateGroups);
    }

    private void candidate(UUID taskId, UserTaskCandidateType type, String value) {
        UserTaskCandidateEntity candidate = new UserTaskCandidateEntity();
        candidate.setId(UUID.randomUUID());
        candidate.setTaskId(taskId);
        candidate.setCandidateType(type);
        candidate.setCandidateValue(value);
        candidateRepository.save(candidate);
    }
}
