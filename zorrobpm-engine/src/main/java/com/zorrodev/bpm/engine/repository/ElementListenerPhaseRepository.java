package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ElementListenerPhaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ElementListenerPhaseRepository extends JpaRepository<ElementListenerPhaseEntity, UUID> {

    /** WO-C8-25: park-check and incident-resolve re-entry — one live phase per token+element. */
    Optional<ElementListenerPhaseEntity> findByProcessInstanceIdAndTokenIdAndBpmnElementId(
        UUID processInstanceId, UUID tokenId, String bpmnElementId);
}
