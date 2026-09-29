package com.zorrodev.bpm.contract.dto.query;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UiUserQuery extends BaseQuery {
    private String username;
    private Boolean active;
}
