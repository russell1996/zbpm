package com.zorrodev.bpm.contract.dto.query;

/**
 * How a task may relate to the person named by {@code relatedToUser} / {@code relatedToGroups}.
 */
public enum UserTaskRelation {
    /** The person is the assignee of the task. */
    ASSIGNEE,
    /** The person is a candidate of the task, personally or through one of the groups. */
    CANDIDATE,
    /** Either of the above. */
    ANY
}
