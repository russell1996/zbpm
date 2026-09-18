package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DmnRule {
    private String id;
    private List<String> inputEntries;
    private List<String> outputEntries;
}
