package com.zorrodev.bpm.engine.service.db;

import java.util.Set;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен ParallelGateways.
 */
public interface ParallelGatewayDbOperations {

    void clearParallelGatewayArrivals(UUID processInstanceId, String gatewayElementId);

    /**
     * WO-C8-38 (C38-2): scope-confined arrival cleanup — arrival rows of the given joins
     * of one instance. Empty collection = no-op (no query).
     */
    void clearParallelGatewayArrivalsInJoins(UUID processInstanceId, java.util.Collection<String> gatewayElementIds);

    /**
     * WO-C8-38 (C38-2): whole-instance arrival cleanup — arrival rows of the instance
     * (its joins will never fire).
     */
    void clearAllParallelGatewayArrivals(UUID processInstanceId);

    Set<String> getParallelGatewayArrivedFlows(UUID processInstanceId, String gatewayElementId);

    void recordParallelGatewayArrival(UUID processInstanceId, String gatewayElementId, String enteredFlowId);

    /**
     * WO-C8-35 (CR-09, ШАГ 1/3): gateway element ids of this instance that still hold open arrival
     * rows — branches parked at a join that has not fired yet.
     */
    Set<String> getGatewaysWithOpenArrivals(UUID processInstanceId);

    Integer getInclusiveExpected(UUID processInstanceId, String gatewayElementId);

    void recordInclusiveExpected(UUID processInstanceId, String gatewayElementId, int expectedCount);

    int decrementPendingBranches(UUID tokenId);

    void setPendingBranches(UUID tokenId, int count);
}
