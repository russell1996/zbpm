package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceAccountWithKeyDTO extends ServiceAccountDTO {
    private String key;
}
