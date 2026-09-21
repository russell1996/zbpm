package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WO-DIFF-5: body of {@code POST /messages/publish} (the message name is the path-less body).
 * <ul>
 *   <li>{@code messageName} — the message to correlate (required);</li>
 *   <li>{@code correlationKey} — optional targeted delivery: only subscriptions whose stored
 *       key matches are woken (null = name-based correlation);</li>
 *   <li>{@code processInstanceId} — optional narrowing to one instance. When BOTH this and
 *       {@code correlationKey} are absent the publication is global: it also starts new
 *       instances via message start events (Zeebe {@code PublishMessage} semantics);</li>
 *   <li>{@code variables} — applied to every woken subscription (and to message-started
 *       instances), same as the engine's internal correlate path.</li>
 * </ul>
 */
@Getter
@Setter
public class PublishMessageDTO {
    @NotBlank(message = "messageName must not be blank")
    private String messageName;

    private String correlationKey;

    private UUID processInstanceId;

    @NotNull(message = "variables must not be null (use [] for no variables)")
    private List<ProcessVariable> variables = new ArrayList<>();
}
