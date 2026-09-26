package com.zorrodev.bpm.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * WO-DIFF-5: outcome of {@link com.zorrodev.bpm.engine.service.ActivityService#publishMessage} —
 * how many waiting subscriptions were woken ({@code correlated}) and how many new instances
 * were started via message start events ({@code started}).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class MessagePublishResult {
    private int correlated;
    private int started;
}
