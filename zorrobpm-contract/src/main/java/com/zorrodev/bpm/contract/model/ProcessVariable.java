package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ProcessVariable {
    private String name;
    private String value;
    private ProcessVariableType type;
    /** null = переменная процесса (root scope); иначе — id активности, к которой
     *  привязана локальная переменная (например, input-мэппинг zeebe:ioMapping). */
    private UUID activityId;
}
