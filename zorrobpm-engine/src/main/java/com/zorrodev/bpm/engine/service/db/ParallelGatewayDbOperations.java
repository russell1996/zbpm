package com.zorrodev.bpm.engine.service.db;

import java.util.Set;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен ParallelGateways.
 */
public interface ParallelGatewayDbOperations {

    void clearParallelGatewayArrivals(UUID processInstanceId, String gatewayElementId);

    Set<String> getParallelGatewayArrivedFlows(UUID processInstanceId, String gatewayElementId);

    void recordParallelGatewayArrival(UUID processInstanceId, String gatewayElementId, String enteredFlowId);

    /**
     * WO-C8-35 (CR-09, ШАГ 1/3): gateway element ids of this instance that still hold open arrival
     * rows \u2014 branches parked at a join that has not fired yet.
     */
    Set<String> getGatewaysWithOpenArrivals(UUID processInstanceId);

    Integer getInclusiveExpected(UUID processInstanceId, String gatewayElementId);

    void recordInclusiveExpected(UUID processInstanceId, String gatewayElementId, int expectedCount);

    int decrementPendingBranches(UUID tokenId);

    void setPendingBranches(UUID tokenId, int count);
}
