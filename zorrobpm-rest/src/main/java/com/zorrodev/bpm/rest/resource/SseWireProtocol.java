package com.zorrodev.bpm.rest.resource;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * WO-AUDIT-9 (шаг 1): проводной протокол SSE — чистые функции построения
 * событий, без I/O и состояния.
 *
 * <p>Выделено из {@link SseEventStreamService} построчно, без смены семантики:
 * {@link #buildLiveEvent} — бывший приватный статик {@code buildLiveEvent}
 * (WO-REL-38: id — позиция курсора строкой; reconnectTime 3000),
 * {@link #HEARTBEAT_COMMENT} — бывший static-final {@code HEARTBEAT_COMMENT}
 * (WO-REL-57: проводная форма {@code ":heartbeat\n\n"} — голый comment,
 * который по спекту SSE никогда не диспатчит MessageEvent).
 */
public final class SseWireProtocol {

    private SseWireProtocol() {
    }

    /** WO-REL-57: heartbeat payload (wire form {@code ":heartbeat\n\n"}). */
    public static final String HEARTBEAT_COMMENT = "heartbeat";

    /**
     * WO-REL-70: immediate hello payload (wire form starts with
     * {@code ":connected\n"} — a bare SSE comment, never a dispatchable
     * MessageEvent, same shape as the REL-57 heartbeat). Sent through the
     * client's own writer queue right after registration (before catchup
     * read + drain), so the first flush carries bytes within milliseconds:
     * a buffering reverse proxy (default {@code proxy_buffering on}) sees
     * the response start at once instead of holding the handshake until
     * the first 15s heartbeat tick, and the browser's {@code onopen} fires
     * immediately.
     */
    public static final String HELLO_COMMENT = "connected";

    /**
     * WO-REL-38: SSE id — позиция курсора (параметр метода уже курсор).
     * WO-REL-47: построение builder'а — чистая функция без I/O (builder
     * отправляется writer'ом клиента позже, последовательно).
     */
    public static SseEmitter.SseEventBuilder buildLiveEvent(
            long cursor, String eventType, Map<String, Object> envelope) {
        return SseEmitter.event()
            .id(String.valueOf(cursor))
            .name(eventType)
            .data(envelope)
            .reconnectTime(3000);
    }

    /**
     * WO-REL-57: heartbeat-комментарий для pump'а (тот же builder, что
     * {@code SseClientInfo.enqueueHeartbeat} клал в очередь напрямую).
     */
    public static SseEmitter.SseEventBuilder heartbeatEvent() {
        return SseEmitter.event().comment(HEARTBEAT_COMMENT);
    }

    /**
     * WO-REL-70: hello-комментарий для немедленной первой отправки (тот же
     * builder-механизм, что heartbeat, другое содержимое — см.
     * {@link #HELLO_COMMENT}).
     */
    public static SseEmitter.SseEventBuilder helloEvent() {
        return SseEmitter.event().comment(HELLO_COMMENT).reconnectTime(3000);
    }
}
