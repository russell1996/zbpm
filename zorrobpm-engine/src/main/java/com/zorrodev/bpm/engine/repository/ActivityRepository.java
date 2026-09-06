package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<ActivityEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ActivityEntity e SET e.status = :status, e.completedAt = :completedAt WHERE e.id = :id")
    void setStatusAndCompletedAt(UUID id, ActivityStatus status, Instant completedAt);

    List<ActivityEntity> findByTokenAndBpmnElementId(UUID token, String bpmnElementId);

    List<ActivityEntity> findByProcessInstanceIdAndStatusIn(UUID processInstanceId, Collection<ActivityStatus> statuses);

    List<ActivityEntity> findByProcessInstanceIdOrderByCreatedAtAsc(UUID processInstanceId);

    List<ActivityEntity> findByTokenAndStatusIn(UUID token, Collection<ActivityStatus> statuses);

    List<ActivityEntity> findByTokenAndBpmnElementIdAndStatusIn(UUID token, String bpmnElementId, Collection<ActivityStatus> statuses);

    /**
     * WO-C8-28: activities with an open canceling-listener phase on one token —
     * the last-closer check for a deferred boundary continuation (all must close
     * before the token proceeds; serialized by the process-instance lock).
     */
    List<ActivityEntity> findByTokenAndPendingCancelingListenerIndexIsNotNull(UUID token);

    /**
     * WO-C8-28: activities with an open canceling-listener phase in one instance —
     * the last-closer check for a deferred process-cancel tail.
     */
    List<ActivityEntity> findByProcessInstanceIdAndPendingCancelingListenerIndexIsNotNull(UUID processInstanceId);
}
