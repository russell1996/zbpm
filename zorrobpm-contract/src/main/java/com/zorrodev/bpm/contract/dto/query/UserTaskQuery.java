package com.zorrodev.bpm.contract.dto.query;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class UserTaskQuery extends BaseQuery {
    private UUID processInstanceId;
    private String assignee;
    private String candidateGroup;
    private String candidateUser;
    private Boolean completed;
    private Boolean assigned;
    // ─── WO-IN-2: new OPTIONAL filters — null/blank keeps the previous behaviour byte-for-byte ───
    /** Exact match on {@code user_tasks.bpmn_element_id} (null/blank = no filter). */
    private String bpmnElementId;
    /** Exact match on {@code user_tasks.form_key} (null/blank = no filter). */
    private String formKey;
    /**
     * "Everything that concerns this person", resolved in ONE query: the task's assignee is the
     * user's username, OR the task's candidate-group list intersects one of the user's groups.
     * Authorization of the VALUE (whose tasks may be asked for) is enforced at the resource layer
     * before this query runs — see {@code QueryResource.getUserTasks}.
     */
    private UUID relatesTo;
    /** White-listed user-task column to sort by; unknown value → 400, never interpolated into SQL. */
    private String sortBy;
    /**
     * "asc" / "desc". Read on its own too: with no {@link #sortBy} it applies to the default
     * {@code createdAt} column, so {@code ?sortOrder=asc} alone means "oldest first"
     * (@verifier finding 6 — the javadoc used to claim the opposite and the field WAS read
     * unconditionally). Absent → DESC, the direction this endpoint has always answered with.
     */
    private String sortOrder;
}