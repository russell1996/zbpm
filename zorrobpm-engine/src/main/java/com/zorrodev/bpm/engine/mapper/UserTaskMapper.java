package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateKind;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserTaskMapper {

    /** Документный дефолт приоритета (BPMN без явного приоритета). */
    public static final int DEFAULT_PRIORITY = 50;

    private final BpmnService bpmnService;
    private final ActivityRepository activityRepository;
    private final UserTaskCandidateRepository userTaskCandidateRepository;

    public UserTask toDTO(UserTaskEntity entity) {
        return toDTO(entity, activityRepository.findById(entity.getId()).orElse(null), loadCandidates(List.of(entity)).getOrDefault(entity.getId(), Map.of()));
    }

    /**
     * WO-PERF-2 (D-01): the activity (lifecycle status) rides the page query itself
     * (to-one JOIN via {@code @EntityGraph} on the paged {@code findAll}) — no separate
     * activity lookup per row or per page. Candidates ride the same batch pattern:
     * ONE {@code ... WHERE user_task_id IN (...)} for the whole page (WO-IN-4).
     */
    public List<UserTask> toDTOs(List<UserTaskEntity> entities) {
        Map<UUID, Map<UserTaskCandidateKind, List<String>>> candidates = loadCandidates(entities);
        return entities.stream()
            .map(e -> toDTO(e, e.getActivity(), candidates.getOrDefault(e.getId(), Map.of())))
            .toList();
    }

    /**
     * WO-IN-4: кандидаты целой страницы ОДНИМ запросом ({@code ... WHERE
     * user_task_id IN (...)}), тем же batch-паттерном, что активности выше.
     * Поштучный findByUserTaskId давал N+1 (23 запроса на страницу из 20 —
     * поймано QueryServiceBulkLoadingIntegrationTests, лимит ≤3).
     */
    private Map<UUID, Map<UserTaskCandidateKind, List<String>>> loadCandidates(List<UserTaskEntity> entities) {
        List<UUID> ids = entities.stream().map(UserTaskEntity::getId).distinct().toList();
        Map<UUID, Map<UserTaskCandidateKind, List<String>>> out = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (UUID id : ids) {
            out.put(id, new EnumMap<>(UserTaskCandidateKind.class));
        }
        for (UserTaskCandidateEntity row : userTaskCandidateRepository.findByUserTaskIdIn(ids)) {
            out.get(row.getUserTaskId()).computeIfAbsent(row.getKind(), k -> new ArrayList<>()).add(row.getCandidate());
        }
        return out;
    }

    private UserTask toDTO(UserTaskEntity entity, ActivityEntity activity, Map<UserTaskCandidateKind, List<String>> candidates) {
        BpmnElementModel element = bpmnService.getProcessDefinitionModelById(entity.getProcessDefinitionId()).getElement(entity.getBpmnElementId());
        UserTask dto = new UserTask();
        dto.setId(entity.getId());
        dto.setName(element.getName());
        dto.setProcessInstanceId(entity.getProcessInstanceId());
        dto.setProcessDefinitionId(entity.getProcessDefinitionId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setCode(entity.getBpmnElementId());
        dto.setFormKey(entity.getFormKey());
        dto.setDueDate(entity.getDueDate());
        dto.setFollowUpDate(entity.getFollowUpDate());
        // WO-C8-30: effective priority, pre-WO null rows read as the docs default 50.
        dto.setPriority(entity.getPriority() != null ? entity.getPriority() : DEFAULT_PRIORITY);
        // WO-IN-4: assignee straight from the row (null = unassigned); candidates from
        // the normalized table — the same rows the ?candidateGroup=/?candidateUser=
        // filters match since WO-IN-3. Empty list (never null) when the task has none.
        dto.setAssignee(entity.getAssignee());
        dto.setCandidateGroups(List.copyOf(candidates.getOrDefault(UserTaskCandidateKind.GROUP, List.of())));
        dto.setCandidateUsers(List.copyOf(candidates.getOrDefault(UserTaskCandidateKind.USER, List.of())));
        // task id == activity id: expose the authoritative lifecycle status for the UI
        if (activity != null) {
            dto.setStatus(activity.getStatus() == null ? null : activity.getStatus().name());
        }
        return dto;
    }
}
