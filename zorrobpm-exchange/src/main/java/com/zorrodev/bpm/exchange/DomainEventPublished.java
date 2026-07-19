package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Spring event published when a domain event envelope is ready to be sent to RabbitMQ (WO-EVT-2).
 */
@Getter
@RequiredArgsConstructor
public class DomainEventPublished {
    private final Map<String, Object> envelope;
}
