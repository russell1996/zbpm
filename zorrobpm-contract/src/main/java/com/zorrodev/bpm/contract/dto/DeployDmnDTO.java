package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class DeployDmnDTO {

    /**
     * WO-AUDIT-13 (F-07): single source of truth for the DMN upload cap (chars, not wire
     * bytes) — 5 MB, the same value as the BPMN deploy cap
     * ({@code AddProcessDefinitionDTO.MAX_BPMN_LENGTH}). Before this WO the DMN path had
     * NO cap at all: neither on the DTO nor in the service.
     */
    public static final int MAX_DMN_LENGTH = 5_000_000;

    /** DMN resource XML text. */
    @Size(max = MAX_DMN_LENGTH, message = "DMN must not exceed 5 MB")
    private String dmn;

    /** Optional owning process definition id (authz scoping, same as DmnService.deploy). */
    private UUID processDefinitionId;
}
