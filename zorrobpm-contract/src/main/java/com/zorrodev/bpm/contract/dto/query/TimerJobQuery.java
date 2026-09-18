package com.zorrodev.bpm.contract.dto.query;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class TimerJobQuery extends BaseQuery {
    private UUID processInstanceId;
    /** true -> already fired; false -> pending; null -> both. */
    private Boolean fired;
}
