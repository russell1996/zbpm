package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class CompleteTaskDTO {
    /**
     * WO-API-1: null запрещён явно (был NPE-путь → 500: complete с
     * {@code {"variables":null}} падал мимо валидатора формы). Пустой список —
     * валиден на уровне DTO (required-проверка — дело FormValidator, F16).
     */
    @NotNull(message = "variables must not be null (use [] for no variables)")
    private List<ProcessVariable> variables = new ArrayList<>();
}
