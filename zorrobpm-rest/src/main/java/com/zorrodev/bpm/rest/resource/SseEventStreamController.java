package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        SseEmitter emitter = new SseEmitter(0L); // no timeout

        // Send catchup events if Last-Event-ID is provided
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                long sinceSequence = Long.parseLong(lastEventId);
                sseEventStreamService.sendCatchupEvents(emitter, sinceSequence, principal, processDefinitionKey);
            } catch (NumberFormatException e) {
                log.warn("Invalid Last-Event-ID: {}", lastEventId);
            }
        }

        // Register client for future events
        String clientId = sseEventStreamService.registerClient(emitter, principal, type, processInstanceId, processDefinitionKey);

        log.info("SSE stream opened: clientId={}, type={}, processInstanceId={}, processDefinitionKey={}, lastEventId={}",
            clientId, type, processInstanceId, processDefinitionKey, lastEventId);

        return emitter;
    }

    private Principal getPrincipal(HttpServletRequest request) {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }
}
