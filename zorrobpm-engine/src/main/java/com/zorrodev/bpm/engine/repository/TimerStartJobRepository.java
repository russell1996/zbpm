package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimerStartJobRepository extends JpaRepository<TimerStartJobEntity, UUID> {

    List<TimerStartJobEntity> findByFiredFalseAndDueAtLessThanEqual(Instant now);

    @Modifying
    @Query("UPDATE TimerStartJobEntity t SET t.fired = true WHERE t.id = :id AND t.fired = false")
    int claimTimerStartJob(@Param("id") UUID id);

    @Modifying
    void deleteByProcessKey(String processKey);
}
