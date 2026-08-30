package com.zorrodev.bpm.contract.dto.query;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class UserTaskQuery extends BaseQuery {
    private UUID processInstanceId;
    private String bpmnElementId;
    private String formKey;
    private String assignee;
    private String candidateGroup;
    private String candidateUser;
    private Boolean completed;
    private Boolean assigned;

    /**
     * Together with {@link #relatedToGroups} and {@link #relation} forms the "task relates to this
     * person" block. Conditions inside the block are combined with OR; the block as a whole is
     * combined with the other filters using AND, like every other filter.
     */
    private String relatedToUser;
    private List<String> relatedToGroups = new ArrayList<>();
    /**
     * Defaults to {@link UserTaskRelation#ANY} when the block is given without it.
     */
    private UserTaskRelation relation;
}
