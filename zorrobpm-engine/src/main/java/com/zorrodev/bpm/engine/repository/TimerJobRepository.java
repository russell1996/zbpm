package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimerJobRepository extends JpaRepository<TimerJobEntity, UUID>, JpaSpecificationExecutor<TimerJobEntity> {

    List<TimerJobEntity> findByFiredFalseAndDueAtLessThanEqual(Instant now);

    static Specification<TimerJobEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, cb) -> cb.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<TimerJobEntity> byFired(boolean fired) {
        return (root, query, cb) -> cb.equal(root.get("fired"), fired);
    }
}
