package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /events/stream — Server-Sent Events for domain events (ADR-7, WO-EVT-4).
 * JWT-auth (cookie/Bearer), Last-Event-ID for reconnect catchup.
 */
@Slf4j
@RestController
@RequestMapping("/events/stream")
@RequiredArgsConstructor
public class SseEventStreamController {

    private final SseEventStreamService sseEventStreamService;

    /**
     * WO-SEC-67 (F13): bounded stream lifetime. 30 minutes = the JWT access
     * TTL ({@code zorrobpm.security.jwt-ttl-minutes:30}) — a stream never
     * outlives the session that opened it. Expiry fires the emitter's
     * onTimeout → the client entry is removed (existing callback, untouched)
     * and the browser reconnects by SSE convention (all sends already carry
     * {@code reconnectTime(3000)}), re-authenticating on the fresh request.
     * Configurable (P-13 — no hardcoded env-sensitive lifetimes).
     */
    @Value("${zorrobpm.sse.emitter-timeout-ms:1800000}")
    private long emitterTimeoutMs = 30 * 60 * 1000L;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletRequest request) {

        Principal principal = getPrincipal(request);
        if (principal == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.completeWithError(new SecurityException("Unauthorized"));
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);

        try {
            // WO-REL-37 (F14): регистрация live-подписки ДО чтения catchup + буфер
            // пересечения — событие между этими шагами раньше терялось навсегда.
            // Drain: live-события с sequence <= границы catchup отбрасываются (дубль),
            // новее — доставляются (не потеряны).
            String clientId = sseEventStreamService.registerBufferedClient(
                emitter, principal, type, processInstanceId, processDefinitionKey);

            // Send catchup events if Last-Event-ID is provided (cursor+pages, WO-REL-37)
            long boundary = 0;
            if (lastEventId != null && !lastEventId.isBlank()) {
                try {
                    long sinceSequence = Long.parseLong(lastEventId);
                    boundary = sseEventStreamService.sendCatchupEvents(
                        emitter, sinceSequence, principal, processDefinitionKey, type, processInstanceId);
                } catch (NumberFormatException e) {
                    log.warn("Invalid Last-Event-ID: {}", lastEventId);
                }
            }

            sseEventStreamService.drainBufferedClient(clientId, boundary);

            log.info("SSE stream opened: clientId={}, type={}, processInstanceId={}, processDefinitionKey={}, lastEventId={}",
                clientId, type, processInstanceId, processDefinitionKey, lastEventId);
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            log.error("Error setting up SSE stream", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private Principal getPrincipal(HttpServletRequest request) {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }
}
