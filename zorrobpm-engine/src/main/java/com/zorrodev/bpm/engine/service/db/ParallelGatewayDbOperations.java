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

    Integer getInclusiveExpected(UUID processInstanceId, String gatewayElementId);

    void recordInclusiveExpected(UUID processInstanceId, String gatewayElementId, int expectedCount);

    int decrementPendingBranches(UUID tokenId);

    void setPendingBranches(UUID tokenId, int count);
}
