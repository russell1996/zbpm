package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.EventQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WO-AUDIT-9 (шаг 5c): replay/догон по {@code Last-Event-ID} — бывший
 * {@code sendCatchupEvents} (+ {@code intersect}, {@code cursorOf})
 * {@code SseEventStreamService} (WO-REL-37 F12/F14, WO-REL-38), перенесённый
 * построчно, без смены семантики.
 *
 * <p>Зависимости — constructor-injected: {@link EventQueryService}
 * (envelope-окна из БД), {@link EventAuthzResolver} (стартовый pdId-снимок).
 * Отправка идёт напрямую в переданный emitter (синхронный HTTP-путь
 * контроллера, не writer pump).
 */
@Slf4j
public final class SseCatchupReader {

    private final EventQueryService eventQueryService;
    private final EventAuthzResolver eventAuthzResolver;

    public SseCatchupReader(EventQueryService eventQueryService,
            EventAuthzResolver eventAuthzResolver) {
        this.eventQueryService = eventQueryService;
        this.eventAuthzResolver = eventAuthzResolver;
    }

    /**
     * Sends catchup events from the database for reconnect (Last-Event-ID).
     *
     * <p>WO-REL-37 (F12/F14): единый путь с live и REST — тот же
     * {@code EventQueryService.findEventEnvelopes} (тот же фильтр type/piId +
     * гранты, та же пагинация maxResults+1/hasMore, фильтрация в SQL до окна).
     * Envelope строит сервис (null-safe LinkedHashMap, не Map.of) — штатный
     * null elementId больше не роняет весь backlog (F12). Возвращает границу
     * (max выданной позиции курсора, или since — если ничего не выдано) для
     * дедупа пересечения catchup→live.
     *
     * <p>WO-REL-38: граница и SSE id — feed-позиция (см. cursorOf), входной
     * since трактуется в том же домене (клиент хранит последний полученный
     * id, который теперь позиция).
     *
     * @return max выданной позиции (exclusive-граница live-буфера)
     */
    public long sendCatchupEvents(SseEmitter emitter, long sinceSequence, Principal principal,
                                    String processDefinitionKeyFilter) {
        return sendCatchupEvents(emitter, sinceSequence, principal, processDefinitionKeyFilter,
            null, null);
    }

    /**
     * Полная форма с теми же фильтрами, что live-подписка (F14: один и тот же
     * фильтр для catchup и live — type + processInstanceId).
     */
    public long sendCatchupEvents(SseEmitter emitter, long sinceSequence, Principal principal,
                                    String processDefinitionKeyFilter,
                                    String typeFilter, String processInstanceIdFilter) {
        Collection<UUID> allowedPdIds = eventAuthzResolver.readableRuntimePdIds(principal, null);
        List<UUID> keyPdIds = eventQueryService.resolveKeyPdIds(processDefinitionKeyFilter);
        Collection<UUID> pdFilter = intersect(allowedPdIds, keyPdIds);
        if (pdFilter != null && pdFilter.isEmpty()) {
            return sinceSequence;
        }

        UUID piId = null;
        if (processInstanceIdFilter != null && !processInstanceIdFilter.isBlank()) {
            try {
                piId = UUID.fromString(processInstanceIdFilter);
            } catch (IllegalArgumentException e) {
                return sinceSequence;
            }
        }

        // Пагинация за пределами 100: страницами по 100, пока есть hasMore (F14:
        // "100 на страницу", не "100 на весь backlog").
        long boundary = sinceSequence;
        while (true) {
            List<Map<String, Object>> envelopes =
                eventQueryService.findEventEnvelopes(boundary, pdFilter, piId, typeFilter, 100);
            boolean hasMore = envelopes.size() > 100;
            List<Map<String, Object>> page = hasMore ? envelopes.subList(0, 100) : envelopes;
            for (Map<String, Object> envelope : page) {
                // WO-REL-38: SSE id и граница — позиция курсора (feedPosition;
                // fallback — sequence, см. cursorOf). Браузер шлёт её назад
                // как Last-Event-ID — тот же домен, что since у REST.
                long cursor = cursorOf(envelope, boundary);
                try {
                    SseEmitter.SseEventBuilder sseEvent = SseEmitter.event()
                        .id(String.valueOf(cursor))
                        .name((String) envelope.get("type"))
                        .data(envelope)
                        .reconnectTime(3000);
                    emitter.send(sseEvent);
                } catch (Exception e) {
                    if (isTransportFailure(e)) {
                        // F-04 (WO-AUDIT-10): устойчивый транспортный сбой (мёртвое
                        // соединение / закрытый emitter) — фатален: без этого boundary
                        // не двигается, hasMore остаётся true и цикл перечитывает ту же
                        // страницу вечно — hot-loop в потоке Tomcat + 100
                        // ERROR-стектрейсов на страницу. Останавливаем catch-up и
                        // возвращаем управление (контроллер доделает регистрацию/слив).
                        log.error("SSE transport failed during catch-up at boundary {}; stopping catch-up",
                            boundary, e);
                        return boundary;
                    }
                    // F12: одна битая запись (сбой сериализации) не обрывает остаток
                    // backlog — но курсор двигается НАД ней (F-04): иначе следующая
                    // страница читается с той же позиции и запись повторяется вечно.
                    log.error("Skipping catchup event (send/serialization failure), continuing with the rest", e);
                }
                if (cursor > boundary) {
                    boundary = cursor;
                }
            }
            if (!hasMore) {
                break;
            }
        }
        return boundary;
    }

    /**
     * F-04 (WO-AUDIT-10): сбой send, который убивает весь catch-up.
     *
     * <p>Транспортные сбои: {@link java.io.IOException} (мёртвое соединение —
     * broken pipe / клиент отвалился; в Spring 7 приходит из flush()
     * {@code DefaultSseEmitterHandler}) и {@link IllegalStateException}
     * закрытого emitter'а («ResponseBodyEmitter has already completed»).
     * Сбой сериализации одной записи — {@code HttpMessageNotWritableException}
     * (unchecked, из converter.write в том же handler'е, НЕ IOException) —
     * транспортным НЕ считается: запись пропускается, курсор движется.
     */
    static boolean isTransportFailure(Exception e) {
        return e instanceof java.io.IOException || e instanceof IllegalStateException;
    }

    /** null = unrestricted; пересечение "see all" с key-фильтром даёт key-фильтр. */
    private static Collection<UUID> intersect(Collection<UUID> allowed, Collection<UUID> extra) {
        if (allowed == null) return extra;
        if (extra == null) return allowed;
        Set<UUID> result = new java.util.LinkedHashSet<>(allowed);
        result.retainAll(new java.util.LinkedHashSet<>(extra));
        return new java.util.ArrayList<>(result);
    }

    /**
     * WO-REL-38: позиция курсора из envelope. Новые envelope несут
     * {@code feedPosition}; старые/синтетические (тесты, прямые вызовы) —
     * только {@code sequence}, тогда курсором служит он (совместимость чтения,
     * не записи: прод всегда пишет обе).
     */
    private static long cursorOf(Map<String, Object> envelope, long fallback) {
        Object fp = envelope.get("feedPosition");
        if (fp instanceof Number n) {
            return n.longValue();
        }
        Object seq = envelope.get("sequence");
        if (seq instanceof Number n) {
            return n.longValue();
        }
        return fallback;
    }
}
