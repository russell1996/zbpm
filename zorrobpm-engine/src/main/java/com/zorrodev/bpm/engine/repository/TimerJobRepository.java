package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimerJobRepository extends JpaRepository<TimerJobEntity, UUID>, JpaSpecificationExecutor<TimerJobEntity> {

    List<TimerJobEntity> findByFiredFalseAndDueAtLessThanEqual(Instant now);

    @Modifying
    @Query("UPDATE TimerJobEntity t SET t.fired = true WHERE t.id = :id AND t.fired = false")
    int claimTimerJob(@Param("id") UUID id);

    @Modifying
    @Query("DELETE FROM TimerJobEntity t WHERE t.processInstanceId = :processInstanceId")
    void deleteByProcessInstanceId(@Param("processInstanceId") UUID processInstanceId);

    static Specification<TimerJobEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, cb) -> cb.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<TimerJobEntity> byFired(boolean fired) {
        return (root, query, cb) -> cb.equal(root.get("fired"), fired);
    }
}
