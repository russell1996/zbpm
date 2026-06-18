package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ParallelGatewayEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ParallelGatewayRepository extends JpaRepository<ParallelGatewayEntity, UUID> {

    @Query("SELECT pg.enteredFlowId FROM ParallelGatewayEntity pg "
        + "WHERE pg.processInstanceId = :processInstanceId AND pg.gatewayElementId = :gatewayElementId")
    List<String> findEnteredFlows(UUID processInstanceId, String gatewayElementId);

    boolean existsByProcessInstanceIdAndGatewayElementIdAndEnteredFlowId(
        UUID processInstanceId, String gatewayElementId, String enteredFlowId);

    @Modifying
    void deleteByProcessInstanceIdAndGatewayElementId(UUID processInstanceId, String gatewayElementId);
}
