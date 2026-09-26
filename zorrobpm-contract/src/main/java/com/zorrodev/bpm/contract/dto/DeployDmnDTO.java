package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class DeployDmnDTO {
    /** DMN resource XML text. */
    private String dmn;
    /** Optional owning process definition id (authz scoping, same as DmnService.deploy). */
    private UUID processDefinitionId;
}
