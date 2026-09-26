package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApiKeyWithSecretDTO extends ApiKeyDTO {
    /** Plaintext key — shown ONCE at creation/rotate. Never stored. */
    private String key;
}
