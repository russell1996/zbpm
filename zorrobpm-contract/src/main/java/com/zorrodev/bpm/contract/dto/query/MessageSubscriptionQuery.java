package com.zorrodev.bpm.contract.dto.query;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class MessageSubscriptionQuery extends BaseQuery {
    private UUID processInstanceId;
    /** true -> already consumed; false -> waiting; null -> both. */
    private Boolean consumed;
}
