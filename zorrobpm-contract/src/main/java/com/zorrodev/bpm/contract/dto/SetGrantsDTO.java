package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SetGrantsDTO {
    private List<GrantEntry> grants;

    @Getter
    @Setter
    public static class GrantEntry {
        private String processKey;
        /** null when full=true */
        private String permissions;
        private boolean full;
    }
}
