package com.zorrodev.bpm.engine.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * WO-REL-21: composite PK of {@link IdempotencyRecord} (client key + endpoint).
 */
public class IdempotencyRecordId implements Serializable {

    private String idemKey;
    private String endpoint;
    private String credentialHash;

    public IdempotencyRecordId() {
    }

    public IdempotencyRecordId(String idemKey, String endpoint, String credentialHash) {
        this.idemKey = idemKey;
        this.endpoint = endpoint;
        this.credentialHash = credentialHash;
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

    public String getCredentialHash() {
        return credentialHash;
    }

    public void setCredentialHash(String credentialHash) {
        this.credentialHash = credentialHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyRecordId that)) return false;
        return Objects.equals(idemKey, that.idemKey) && Objects.equals(endpoint, that.endpoint)
            && Objects.equals(credentialHash, that.credentialHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idemKey, endpoint, credentialHash);
    }
}
