package com.zorrodev.bpm.contract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssignUserTaskDTO {
    @NotBlank
    private String assignee;
}
