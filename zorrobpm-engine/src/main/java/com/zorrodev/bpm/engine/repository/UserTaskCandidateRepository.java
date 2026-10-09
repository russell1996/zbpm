package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserTaskCandidateRepository
    extends JpaRepository<UserTaskCandidateEntity, UserTaskCandidateEntity.UserTaskCandidateId> {

    /** Кандидаты одной задачи — читается ретешен-чеком retention и тестами согласованности. */
    List<UserTaskCandidateEntity> findByUserTaskId(UUID userTaskId);

    /**
     * WO-IN-4: кандидаты целой страницы задач одним запросом (маппер грузит
     * кандидатов тем же batch-паттерном, что активности в UserTaskMapper —
     * N+1 здесь ронял QueryServiceBulkLoadingIntegrationTests: 23 вместо ≤3).
     */
    List<UserTaskCandidateEntity> findByUserTaskIdIn(Collection<UUID> userTaskIds);
}
