package com.zorrodev.bpm.contract.model;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    /**
     * WO-VT-1: только для шаблонов переменных (presets) и только для {@code STRING}.
     * {@code true} = пустая строка — это значение; {@code null}/false + пустое
     * значение = «спросить при запуске». Вне presets поле не используется и не
     * сериализуется (NON_NULL), поэтому существующие пути выполнения не меняются.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean allowEmptyString;
}
