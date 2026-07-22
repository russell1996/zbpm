package com.zorrodev.bpm.contract.dto.query;

import com.zorrodev.bpm.contract.model.ProcessVariableType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class VariableQuery {
    @Min(0) private Integer pageIndex = 0;
    @Min(1) @Max(200) private Integer pageSize = 10;
    private UUID processInstanceId;
    private String name;
    private ProcessVariableType type;
    private String value;
}
