package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CreateServiceAccountDTO {
    private String name;
    private List<String> permissions;
}
