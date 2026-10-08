package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.UUID;

/**
 * WO-AUDIT-9 (шаг 1): identity подписчика SSE — чистая immutable запись,
 * без writer-состояния.
 *
 * <p>Те же девять компонентов, что identity-часть бывшего внутреннего
 * {@code SseEventStreamService.SseClientInfo} (WO-REL-47: «Record components
 * stay the identity … read by the authz paths»); writer-состояние
 * (mode + queue + pump flag + counters) живёт в {@link SseClientSession},
 * которая ссылается на этот дескриптор.
 */
public record SseClientDescriptor(
        String clientId,
        SseEmitter emitter,
        Principal principal,
        /**
         * WO-SEC-67: JWT token_version frozen at registration. The liveness
         * check compares the row's CURRENT version against this — logout bumps
         * the row (the only version writer in prod), so a mismatch means
         * "issued before the revoke". ServicePrincipal streams carry -1
         * (unused — their liveness is the key row, not a version).
         */
        int tokenVersion,
        Collection<UUID> allowedPdIds,
        String typeFilter,
        String processInstanceIdFilter,
        String processDefinitionKeyFilter) {
}
