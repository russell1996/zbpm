package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Records the arrival of one branch at a parallel-gateway join, identified by the incoming flow it
 * arrived through. A join fires once arrivals exist for every incoming flow; the rows are then
 * deleted ("consumed") so that a process looping back through the same join starts counting afresh
 * instead of re-firing on stale arrivals.
 */
@Getter
@Setter
@Entity
@Table(name = "parallel_gateways")
public class ParallelGatewayEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private String gatewayElementId;
    private String enteredFlowId;
    private Instant createdAt;
    /** Set only on an inclusive-join marker row (entered_flow_id NULL): how many branches the
     *  inclusive split activated, i.e. how many arrivals the join must wait for. */
    private Integer expectedCount;
}
