package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcessDefinitionsQueryParameters {
    @Min(0) private Integer pageIndex = 0;
    @Min(1) @Max(200) private Integer pageSize = 20;
    private String name;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private Boolean latestVersionOnly;
    /** Sort direction: "asc" (default) or "desc". Applied to (name, version). */
    private String order;
    /** WO-ENG-9: when true, archived process keys are included in the list (default: hidden). */
    private Boolean includeArchived;
}
