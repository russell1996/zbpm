package com.zorrodev.bpm.exchange;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

/**
 * Spring event asking the messaging layer to make sure a job queue exists for each of these job
 * types (WO-REL-16).
 *
 * <p>Job queues used to be declared lazily, on the first message actually sent to them — so a job
 * type whose queue had never been created (fresh environment, reset broker volume, or a job type
 * that had simply never run yet) had no queue on the broker until some process instance reached a
 * service task of that type. The engine knows every job type at deployment time (parsed from
 * {@code zeebe:taskDefinition}), so it announces them instead of waiting for traffic.
 *
 * <p>Published by the engine on deployment and on startup; consumed by the messaging module.
 * The engine deliberately does not declare queues itself — {@code zorrobpm-rabbitmq} does not
 * depend on {@code zorrobpm-engine}, the two only meet through events in this module (same
 * arrangement as {@link ServiceTaskEnqueued}).
 *
 * <p>Handling must be idempotent and best-effort: an unreachable broker may not fail a deployment
 * or block startup — the lazy declare on first send remains as the fallback.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class JobQueuesRequested {
    private Set<String> jobTypes;
}
