package com.zorrodev.bpm.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * WO-DIFF-5: result of {@code POST /messages/publish}.
 * A bare {@code 200 OK} cannot distinguish "correlated" from "nobody was listening",
 * so the response carries both counters: subscriptions woken ({@code correlated}) and
 * instances started via message start events ({@code started}).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class MessagePublishResultDTO {
    private int correlated;
    private int started;
}
