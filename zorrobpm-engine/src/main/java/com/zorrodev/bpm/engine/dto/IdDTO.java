package com.zorrodev.bpm.engine.dto;

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
     * WO-AUDIT-8 (A-NEW4-15): TRUE — вызов поглощён идемпотентным guard'ом
     * (повторный complete, ничего не изменил); null — обычное завершение.
     * Копируется в contract-DTO в {@code RuntimeOperationSupport.toDTO}.
     */
    private Boolean alreadyCompleted;

    public IdDTO(UUID id) {
        this.id = id;
    }

    public IdDTO(UUID id, Boolean alreadyCompleted) {
        this.id = id;
        this.alreadyCompleted = alreadyCompleted;
    }
}
