package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * WO-VT-1 (п.6-бис): смена видимости PRIVATE↔PROCESS — владелец или админ процесса.
 */
@Getter
@Setter
public class PresetVisibilityDTO {
    private String visibility;
}
