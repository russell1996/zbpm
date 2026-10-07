package com.zorrodev.bpm.contract.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class IdDTO {
    private UUID id;

    /**
     * WO-AUDIT-8 (A-NEW4-15): повторный complete — наблюдаемый, без breaking.
     * {@code TRUE} — вызов ничего не изменил: задача уже была завершена
     * (идемпотентный guard поглотил дубликат). {@code null}/отсутствует — обычный
     * вызов (первое завершение): поле НЕ сериализуется
     * ({@code NON_NULL} — прецедент {@code JobDetailModel.dispatchPhase}),
     * поэтому старый клиент, не знающий поля, видит байтово тот же ответ.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean alreadyCompleted;

    public IdDTO(UUID id) {
        this.id = id;
    }

    public IdDTO(UUID id, Boolean alreadyCompleted) {
        this.id = id;
        this.alreadyCompleted = alreadyCompleted;
    }
}
