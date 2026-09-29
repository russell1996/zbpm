package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.entity.ParallelGatewayEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.repository.ParallelGatewayRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * WO-DEBT-1d: домен ParallelGateways — реализация.
 * Перенесено 1:1 из DBServiceImpl (7 методов), инжектирует только 2 репозитория.
 */
@Service
@RequiredArgsConstructor
public class ParallelGatewayDbOperationsImpl implements ParallelGatewayDbOperations {

    private final ParallelGatewayRepository parallelGatewayRepository;
    private final TokenRepository tokenRepository;

    @Override
    public void recordParallelGatewayArrival(UUID processInstanceId, String gatewayElementId, String enteredFlowId) {
        if (parallelGatewayRepository.existsByProcessInstanceIdAndGatewayElementIdAndEnteredFlowId(
                processInstanceId, gatewayElementId, enteredFlowId)) {
            return;
        }
        ParallelGatewayEntity entity = new ParallelGatewayEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessInstanceId(processInstanceId);
        entity.setGatewayElementId(gatewayElementId);
        entity.setEnteredFlowId(enteredFlowId);
        entity.setCreatedAt(Instant.now());
        parallelGatewayRepository.save(entity);
    }

    @Override
    public Set<String> getParallelGatewayArrivedFlows(UUID processInstanceId, String gatewayElementId) {
        return new HashSet<>(parallelGatewayRepository.findEnteredFlows(processInstanceId, gatewayElementId));
    }

    @Override
    public void clearParallelGatewayArrivals(UUID processInstanceId, String gatewayElementId) {
        parallelGatewayRepository.deleteByProcessInstanceIdAndGatewayElementId(processInstanceId, gatewayElementId);
    }

    @Override
    public void recordInclusiveExpected(UUID processInstanceId, String gatewayElementId, int expectedCount) {
        ParallelGatewayEntity entity = new ParallelGatewayEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessInstanceId(processInstanceId);
        entity.setGatewayElementId(gatewayElementId);
        // marker row (holds the expected count, not an arrival); entered_flow_id is NOT NULL in the
        // schema, so reuse the gateway id and distinguish marker rows by expected_count being set.
        entity.setEnteredFlowId(gatewayElementId);
        entity.setExpectedCount(expectedCount);
        entity.setCreatedAt(Instant.now());
        parallelGatewayRepository.save(entity);
    }

    @Override
    public Integer getInclusiveExpected(UUID processInstanceId, String gatewayElementId) {
        return parallelGatewayRepository.findExpectedCounts(processInstanceId, gatewayElementId)
            .stream().findFirst().orElse(null);
    }

    @Override
    public void setPendingBranches(UUID tokenId, int count) {
        TokenEntity entity = tokenRepository.findById(tokenId).orElseThrow();
        entity.setPendingBranches(count);
        tokenRepository.save(entity);
    }

    /**
     * WO-REL-40 (B-5): atomic decrement. Reads the row with
     * {@code SELECT ... FOR UPDATE} ({@link com.zorrodev.bpm.engine.repository.TokenRepository#findByIdForUpdate})
     * inside this method's own transaction, so two branches finishing in
     * parallel serialise on the row lock instead of both reading the same
     * counter and writing back the same (lost-update) value. The returned int
     * keeps the FinishBranch contract (-1 = no counter, 0 = last branch).
     */
    @Override
    @Transactional
    public int decrementPendingBranches(UUID tokenId) {
        TokenEntity entity = tokenRepository.findByIdForUpdate(tokenId).orElseThrow();
        Integer current = entity.getPendingBranches();
        if (current == null) {
            return -1; // linear process — caller decides
        }
        int next = current - 1;
        entity.setPendingBranches(next);
        tokenRepository.save(entity);
        return next;
    }
}
