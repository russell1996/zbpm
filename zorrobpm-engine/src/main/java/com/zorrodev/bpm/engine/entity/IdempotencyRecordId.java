package com.zorrodev.bpm.engine.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * WO-REL-21/32: composite PK of {@link IdempotencyRecord} (client key + endpoint +
 * actor_id — стабильный субъект, F05).
 */
public class IdempotencyRecordId implements Serializable {

    private String idemKey;
    private String endpoint;
    private String actorId;

    public IdempotencyRecordId() {
    }

    public IdempotencyRecordId(String idemKey, String endpoint, String actorId) {
        this.idemKey = idemKey;
        this.endpoint = endpoint;
        this.actorId = actorId;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public void setIdemKey(String idemKey) {
        this.idemKey = idemKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyRecordId that)) return false;
        return Objects.equals(idemKey, that.idemKey) && Objects.equals(endpoint, that.endpoint)
            && Objects.equals(actorId, that.actorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idemKey, endpoint, actorId);
    }
}
