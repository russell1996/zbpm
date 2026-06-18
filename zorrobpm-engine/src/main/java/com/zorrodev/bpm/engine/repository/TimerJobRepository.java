package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimerJobRepository extends JpaRepository<TimerJobEntity, UUID> {

    List<TimerJobEntity> findByFiredFalseAndDueAtLessThanEqual(Instant now);
}
