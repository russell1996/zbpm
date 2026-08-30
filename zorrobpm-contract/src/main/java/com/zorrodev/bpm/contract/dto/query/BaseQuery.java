package com.zorrodev.bpm.contract.dto.query;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class BaseQuery {
    private UUID id;
    private Integer pageIndex = 0;
    private Integer pageSize = 10;
    /**
     * Field to order by. Only fields declared as sortable for the concrete query type are
     * accepted; anything else is rejected. Without it the order stays whatever it was before
     * sorting existed.
     */
    private String sort;
    /**
     * Order direction, applied only together with {@link #sort}.
     */
    private SortDirection direction;
}
