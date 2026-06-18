package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimerStartJobRepository extends JpaRepository<TimerStartJobEntity, UUID> {

    List<TimerStartJobEntity> findByFiredFalseAndDueAtLessThanEqual(Instant now);

    @Modifying
    void deleteByProcessKey(String processKey);
}
