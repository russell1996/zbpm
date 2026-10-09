package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /events/stream — Server-Sent Events for domain events (ADR-7, WO-EVT-4).
 * JWT-auth (cookie/Bearer), Last-Event-ID for reconnect catchup.
 *
 * <p>WO-REL-70: успех (200 стрим) выставляет {@code X-Accel-Buffering: no} +
 * {@code Cache-Control: no-cache, no-transform} и шлёт немедленный
 * {@code :connected} (см. {@link SseEventStreamService#sendImmediateHello}):
 * внешний edge-nginx с дефолтным {@code proxy_buffering on} иначе держит
 * handshake в буфере, и EventSource висит в «pending». Ошибочные ответы
 * (401 от JwtAuthFilter, 429 от RateLimitFilter) идут МИМО этого метода —
 * заголовки на них не ставятся и их форма не меняется.
 * {@code Connection: keep-alive} намеренно не трогается (hop-by-hop).
 */
@Slf4j
@RestController
@RequestMapping("/events/stream")
@RequiredArgsConstructor
public class SseEventStreamController {

    /**
     * WO-REL-70: заставляет nginx (и любой RFC-совместимый reverse proxy)
     * выключить буферизацию ЭТОГО ответа: nginx по умолчанию не буферизует
     * ответ с {@code X-Accel-Buffering: no} от апстрима (если edge не задан
     * {@code proxy_ignore_headers X-Accel-Buffering} — тогда нужны прямые
     * директивы на edge, см. отчёт WO-REL-70 §edge).
     */
    static final String HDR_X_ACCEL_BUFFERING = "X-Accel-Buffering";
    /** WO-REL-70: SSE-поток некэшируем и не подлежит transform-сжатию прокси. */
    static final String HDR_CACHE_CONTROL = "Cache-Control";
    static final String CACHE_CONTROL_VALUE = "no-cache, no-transform";

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

    /**
     * WO-REL-70: входная точка Spring MVC (аннотации маппинга — ЗДЕСЬ, на
     * 5-параметровой сигнатуре: её держит сигнатурный контракт
     * {@code SseCharacterizationIT.char9_contract_eventWireFormatAndControllerPath},
     * править чужой characterization-тест под себя (V7) нельзя).
     * {@code HttpServletResponse} Spring резолвит как native-аргумент
     * (не аннотируется) — та же техника, что была до WO. Второй метод ниже —
     * лишь тестовый/legacy вызов без аннотаций (иначе ambiguous mapping).
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletRequest request) {
        return streamInternal(type, processInstanceId, processDefinitionKey,
            lastEventId, request,
            SseResponseHolder.response());
    }

    /**
     * WO-REL-70: вариант с явным {@code HttpServletResponse} (заголовки
     * ставятся напрямую — прямой unit-вызов без request-scope).
     * Без MVC-аннотаций: второй {@code @GetMapping} на то же имя дал бы
     * ambiguous mapping — вход один (метод выше).
     */
    public SseEmitter stream(
            String type,
            String processInstanceId,
            String processDefinitionKey,
            String lastEventId,
            HttpServletRequest request,
            HttpServletResponse response) {
        return streamInternal(type, processInstanceId, processDefinitionKey,
            lastEventId, request, response);
    }

    private SseEmitter streamInternal(
            String type,
            String processInstanceId,
            String processDefinitionKey,
            String lastEventId,
            HttpServletRequest request,
            HttpServletResponse response) {
        Principal principal = getPrincipal(request);
        if (principal == null) {
            SseEmitter emitter = new SseEmitter(0L);
            emitter.completeWithError(new SecurityException("Unauthorized"));
            return emitter;
        }

        // WO-REL-70: только успех несёт anti-buffering заголовки — ветка
        // выше (и фильтры 401/429 до неё) их не ставят. Перегрузка без
        // response (тестовый/legacy вызов) — заголовки ставить некуда.
        if (response != null) {
            response.setHeader(HDR_X_ACCEL_BUFFERING, "no");
            response.setHeader(HDR_CACHE_CONTROL, CACHE_CONTROL_VALUE);
        }

        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);

        try {
            // WO-REL-37 (F14): регистрация live-подписки ДО чтения catchup + буфер
            // пересечения — событие между этими шагами раньше терялось навсегда.
            // Drain: live-события с sequence <= границы catchup отбрасываются (дубль),
            // новее — доставляются (не потеряны).
            String clientId = sseEventStreamService.registerBufferedClient(
                emitter, principal, type, processInstanceId, processDefinitionKey);

            // WO-REL-70: немедленный :connected — через очередь writer'а
            // (single-writer протокол REL-47 не нарушается), ДО чтения
            // catchup: первый flush уходит сразу после return.
            sseEventStreamService.sendImmediateHello(clientId);

            // WO-AUDIT-7: пин catchup-курсора ДО чтения catchup — окно
            // register→read закрыто: проход retention, стартовавший между
            // регистрацией и чтением, увидит пин. Без заголовка — не
            // трекается (клиенту нужны только новые строки).
            if (lastEventId != null && !lastEventId.isBlank()) {
                try {
                    sseEventStreamService.trackCatchupCursor(clientId, Long.parseLong(lastEventId));
                } catch (NumberFormatException e) {
                    log.warn("Invalid Last-Event-ID: {}", lastEventId);
                }
            }

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

    /**
     * WO-REL-70: {@code HttpServletResponse} текущего запроса (для MVC-входа
     * без явного параметра — заголовки ставятся до первой записи).
     * Вне request-scope (прямой вызов) — {@code null}, заголовки пропускаются.
     */
    static final class SseResponseHolder {
        private SseResponseHolder() {
        }

        static HttpServletResponse response() {
            try {
                var attrs = RequestContextHolder.getRequestAttributes();
                if (attrs instanceof ServletRequestAttributes sra) {
                    return sra.getResponse();
                }
                return null;
            } catch (IllegalStateException e) {
                return null;
            }
        }
    }
}
