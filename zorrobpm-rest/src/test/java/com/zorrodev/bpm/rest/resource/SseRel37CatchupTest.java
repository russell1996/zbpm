package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.EventQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WO-REL-37: RED-first против текущего catchup/live кода.
 *
 * <p>F12: catchup собирает {@code Map.of(...)} с nullable elementId —
 * штатный {@code process-instance.started} (elementId=null) роняет весь backlog
 * (NPE → break: ни это событие, ни остаток не доходят). Тест ниже фиксирует
 * ЖЕЛАЕМОЕ (оба события доходят); на текущем коде — RED (второе не доходит).
 *
 * <p>F14: catchup обязан применять ТЕ ЖЕ фильтры, что live (type +
 * processInstanceId), иначе отдаёт события вне подписки. На текущем коде RED
 * (фильтры игнорируются). Пагинация >100 и подписка-до-catchup — в IT ниже.
 */
@ExtendWith(MockitoExtension.class)
class SseRel37CatchupTest {

    @Mock private EventQueryService eventQueryService;
    @Mock private EventAuthzResolver eventAuthzResolver;

    private SseEventStreamService service;

    private static final Principal ADMIN =
        new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");

    @BeforeEach
    void setUp() {
        // WO-SEC-67: +2 ctor args (UiUserLookupService, ApiKeyRepository) — null = no live checks in unit scope.
        service = new SseEventStreamService(eventQueryService, eventAuthzResolver, null,
            new tools.jackson.databind.ObjectMapper(), null, null);
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        // Прод resolveKeyPdIds(null) возвращает null (unrestricted); мок по умолчанию
        // отдал бы пустой список -> intersect дал бы empty -> ранний return без выборки.
        lenient().when(eventQueryService.resolveKeyPdIds(nullable(String.class))).thenReturn(null);
    }

    /**
     * Emitter, захватывающий envelope через публичный {@code builder.build()}
     * (без reflection: поле {@code sb} внутри Spring 7 закрыто JPMS-инкапсуляцией,
     * {@code setAccessible} падает — предыдущая версия захвата молча давала пусто).
     */
    static class CapturingEmitter extends SseEmitter {
        final List<String> sentIds = new ArrayList<>();
        final List<String> sentNames = new ArrayList<>();
        final List<Map<String, Object>> sentEnvelopes = new ArrayList<>();
        int sendCalls = 0;

        CapturingEmitter() {
            super(60_000L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) throws java.io.IOException {
            sendCalls++;
            for (Object dwm : builder.build()) {
                try {
                    java.lang.reflect.Method getData =
                        dwm.getClass().getMethod("getData");
                    Object data = getData.invoke(dwm);
                    if (data instanceof Map<?, ?> envelope) {
                        sentEnvelopes.add((Map<String, Object>) envelope);
                        Object seq = ((Map<?, ?>) envelope).get("sequence");
                        if (seq != null) sentIds.add(String.valueOf(seq));
                        Object type = ((Map<?, ?>) envelope).get("type");
                        if (type != null) sentNames.add(String.valueOf(type));
                    }
                } catch (ReflectiveOperationException e) {
                    throw new java.io.IOException(e);
                }
            }
        }
    }

    /**
     * F-04 (WO-AUDIT-10): мёртвый транспорт — каждый send падает
     * {@link java.io.IOException} (прод-форма: broken pipe / клиент отвалился
     * на догоне; в Spring 7 IOException приходит из flush() handler'а).
     */
    static class TransportDeadEmitter extends SseEmitter {
        int sendCalls = 0;

        TransportDeadEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws java.io.IOException {
            sendCalls++;
            throw new java.io.IOException("simulated broken pipe: client aborted");
        }
    }

    /**
     * F-04 (WO-AUDIT-10): закрытый emitter — каждый send падает
     * {@link IllegalStateException} («ResponseBodyEmitter has already completed»,
     * прод-форма из ResponseBodyEmitter.send при complete=true).
     */
    static class ClosedEmitter extends SseEmitter {
        int sendCalls = 0;

        ClosedEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            sendCalls++;
            throw new IllegalStateException("ResponseBodyEmitter has already completed");
        }
    }

    /**
     * F-04/F12 (WO-AUDIT-10): сбой сериализации ровно ОДНОЙ записи (sequence=100) —
     * {@link org.springframework.http.converter.HttpMessageNotWritableException}.
     * Прод-форма в Spring 7: DefaultSseEmitterHandler.sendInternal →
     * converter.write → unchecked HttpMessageNotWritableException (НЕ IOException).
     */
    static class BrokenRecordEmitter extends SseEmitter {
        static final long BROKEN_SEQUENCE = 100L;
        final List<String> sentIds = new ArrayList<>();
        int sendCalls = 0;

        BrokenRecordEmitter() {
            super(60_000L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) throws java.io.IOException {
            sendCalls++;
            for (Object dwm : builder.build()) {
                try {
                    java.lang.reflect.Method getData = dwm.getClass().getMethod("getData");
                    Object data = getData.invoke(dwm);
                    if (data instanceof Map<?, ?> envelope) {
                        Object seq = envelope.get("sequence");
                        if (seq instanceof Number n && n.longValue() == BROKEN_SEQUENCE) {
                            throw new org.springframework.http.converter.HttpMessageNotWritableException(
                                "simulated serialization failure of one record");
                        }
                        if (seq != null) {
                            sentIds.add(String.valueOf(seq));
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    throw new java.io.IOException(e);
                }
            }
        }
    }

    /**
     * F-04 (WO-AUDIT-10): устойчивый транспортный сбой — метод обязан вернуть
     * управление за конечное число итераций при ОГРАНИЧЕННОМ числе запросов к БД.
     *
     * <p>В проде БД возвращала бы ту же страницу ВЕЧНО (boundary не двигается,
     * hasMore=true) — цикл бесконечный: hot-loop в потоке Tomcat + 100
     * ERROR-стектрейсов на страницу. Тест ограничивает «вечность» 20 страниц
     * (tripwire в моке), чтобы RED-демонстрация была конечной; ассерт — на
     * ограниченность числа запросов, а не на tripwire.
     */
    @Test
    void catchup_transportSendFailure_stopsCatchupWithBoundedDbQueries() throws Exception {
        TransportDeadEmitter emitter = new TransportDeadEmitter();
        List<Map<String, Object>> page = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            page.add(envelope(i, "t.evt", "el"));
        }
        AtomicInteger dbQueries = new AtomicInteger();
        when(eventQueryService.findEventEnvelopes(anyLong(), isNull(), isNull(), isNull(), eq(100)))
            .thenAnswer(inv -> {
                dbQueries.incrementAndGet();
                return dbQueries.get() <= 20
                    ? new ArrayList<>(page)
                    : new ArrayList<Map<String, Object>>();
            });

        long boundary = service.sendCatchupEvents(emitter, 0L, ADMIN, null);

        assertThat(boundary)
            .as("ничего не доставлено — граница равна since")
            .isEqualTo(0L);
        assertThat(dbQueries.get())
            .as("F-04: при устойчивом транспортном сбое запросы к БД ограничены (на текущем коде — цикл до tripwire)")
            .isLessThanOrEqualTo(2);
    }

    /**
     * F-04 (WO-AUDIT-10): закрытый emitter (IllegalStateException) — тот же
     * фатальный класс, что и мёртвый транспорт: catch-up останавливается,
     * управление возвращается, запросы к БД ограничены.
     */
    @Test
    void catchup_closedEmitter_stopsCatchupWithBoundedDbQueries() throws Exception {
        ClosedEmitter emitter = new ClosedEmitter();
        List<Map<String, Object>> page = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            page.add(envelope(i, "t.evt", "el"));
        }
        AtomicInteger dbQueries = new AtomicInteger();
        when(eventQueryService.findEventEnvelopes(anyLong(), isNull(), isNull(), isNull(), eq(100)))
            .thenAnswer(inv -> {
                dbQueries.incrementAndGet();
                return dbQueries.get() <= 20
                    ? new ArrayList<>(page)
                    : new ArrayList<Map<String, Object>>();
            });

        long boundary = service.sendCatchupEvents(emitter, 0L, ADMIN, null);

        assertThat(boundary)
            .as("ничего не доставлено — граница равна since")
            .isEqualTo(0L);
        assertThat(dbQueries.get())
            .as("F-04: IllegalStateException закрытого emitter'а = фатально, цикл не крутится")
            .isLessThanOrEqualTo(2);
    }

    /**
     * F-04/F12 (WO-AUDIT-10): битая запись (сбой сериализации) НЕ обрывает остаток
     * backlog (F12) — и ДВИГАЕТ курсор (F-04): иначе следующая страница читается
     * с той же позиции и битая запись повторяется вечно.
     *
     * <p>Страница 1: seq 1..101 (101 → hasMore, в page — 1..100, битая — 100).
     * Страница 2: seq 101..200 (100 → hasMore=false). После фикса: 1..99 и
     * 101..200 доставлены, boundary=200, 2 запроса к БД. На текущем коде
     * вторая выборка идёт с boundary=99 (последний успешный send) → в реальном
     * БД это 100..200 с hasMore=true → вечный цикл; tripwire ограничивает.
     */
    @Test
    void catchup_brokenLastRecord_advancesCursorAndDeliversRest() throws Exception {
        BrokenRecordEmitter emitter = new BrokenRecordEmitter();
        List<Map<String, Object>> page1 = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            page1.add(envelope(i, "t.evt", "el"));
        }
        List<Map<String, Object>> page2 = new ArrayList<>();
        for (long i = 101; i <= 200; i++) {
            page2.add(envelope(i, "t.evt", "el"));
        }
        AtomicInteger dbQueries = new AtomicInteger();
        when(eventQueryService.findEventEnvelopes(eq(0L), isNull(), isNull(), isNull(), eq(100)))
            .thenAnswer(inv -> {
                dbQueries.incrementAndGet();
                return new ArrayList<>(page1);
            });
        when(eventQueryService.findEventEnvelopes(eq(100L), isNull(), isNull(), isNull(), eq(100)))
            .thenAnswer(inv -> {
                dbQueries.incrementAndGet();
                return new ArrayList<>(page2);
            });
        // Путь текущего кода (RED): повторная выборка с boundary=99; tripwire — 21 страница.
        lenient().when(eventQueryService.findEventEnvelopes(eq(99L), isNull(), isNull(), isNull(), eq(100)))
            .thenAnswer(inv -> {
                dbQueries.incrementAndGet();
                return dbQueries.get() <= 21
                    ? new ArrayList<>(page1)
                    : new ArrayList<Map<String, Object>>();
            });

        long boundary = service.sendCatchupEvents(emitter, 0L, ADMIN, null);

        List<String> expected = new ArrayList<>();
        for (long i = 1; i <= 99; i++) {
            expected.add(String.valueOf(i));
        }
        for (long i = 101; i <= 200; i++) {
            expected.add(String.valueOf(i));
        }
        assertThat(emitter.sentIds)
            .as("F12: битая запись (100) не обрывает остаток; F-04: курсор перепрыгивает её")
            .containsExactlyElementsOf(expected);
        assertThat(boundary)
            .as("граница дошла до конца backlog")
            .isEqualTo(200L);
        assertThat(dbQueries.get())
            .as("F-04: курсор движется — страниц не перечитывается бесконечно")
            .isLessThanOrEqualTo(3);
    }

    private static DomainEventEntity event(long sequence, String type, String elementId) {
        DomainEventEntity e = new DomainEventEntity();
        e.setSequence(sequence);
        e.setId(UUID.randomUUID());
        e.setType(type);
        e.setVersion(1);
        e.setOccurredAt(Instant.now());
        e.setElementId(elementId);
        e.setData(Map.of());
        return e;
    }

    /**
     * F12: started (elementId=null) + следующее событие — ОБА доходят.
     * Мокаем НИЗКИЙ уровень (repository), чтобы тест ловил именно Map.of-NPE
     * прод-кода, а не форму тестовых данных.
     */
    @Test
    void catchup_nullElementId_doesNotBreakBacklog() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        List<Map<String, Object>> page = new ArrayList<>();
        page.add(envelope(1L, "process-instance.started", null));
        page.add(envelope(2L, "activity.completed", "task1"));
        when(eventQueryService.findEventEnvelopes(eq(0L), isNull(), isNull(), isNull(), eq(100)))
            .thenReturn(new ArrayList<>(page));

        long boundary = service.sendCatchupEvents(emitter, 0L, ADMIN, null);

        assertThat(emitter.sendCalls)
            .as("catchup реально слал события на emitter")
            .isGreaterThan(0);
        assertThat(emitter.sentIds)
            .as("оба события backlog доходят, null-elementId не обрывает поток")
            .containsExactly("1", "2");
        assertThat(boundary).isEqualTo(2L);
    }

    /**
     * F14: catchup применяет ТЕ ЖЕ фильтры, что live (type + processInstanceId).
     * Чужое событие (другой тип) не выдаётся клиенту с фильтром.
     */
    @Test
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    void catchup_appliesSameFiltersAsLive() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        when(eventQueryService.findEventEnvelopes(eq(0L), isNull(), isNull(), eq("wanted.type"), eq(100)))
            .thenReturn(new ArrayList<>());

        service.sendCatchupEvents(emitter, 0L, ADMIN, null, "wanted.type", null);

        assertThat(emitter.sentIds).as("чужое событие вне подписки не выдаётся").isEmpty();
    }

    /**
     * F14: пагинация за пределами 100 — backlog 250 доходит целиком, страницами.
     */
    @Test
    void catchup_beyondHundred_pagesThroughWholeBacklog() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        List<Map<String, Object>> first = new ArrayList<>();
        for (long i = 1; i <= 100; i++) first.add(envelope(i, "t.evt", "el"));
        // findEventEnvelopes возвращает maxResults+1 при hasMore (контракт WO-INT-7)
        List<Map<String, Object>> firstWithExtra = new ArrayList<>(first);
        firstWithExtra.add(envelope(101L, "t.evt", "el"));
        List<Map<String, Object>> rest = new ArrayList<>();
        for (long i = 101; i <= 250; i++) rest.add(envelope(i, "t.evt", "el"));

        when(eventQueryService.findEventEnvelopes(eq(0L), isNull(), isNull(), isNull(), eq(100)))
            .thenReturn(firstWithExtra);
        when(eventQueryService.findEventEnvelopes(eq(100L), isNull(), isNull(), isNull(), eq(100)))
            .thenReturn(new ArrayList<>(rest.subList(0, 101)));
        when(eventQueryService.findEventEnvelopes(eq(200L), isNull(), isNull(), isNull(), eq(100)))
            .thenReturn(new ArrayList<>(rest.subList(100, 150)));

        long boundary = service.sendCatchupEvents(emitter, 0L, ADMIN, null);

        assertThat(emitter.sentIds).hasSize(250);
        assertThat(emitter.sentIds.get(0)).isEqualTo("1");
        assertThat(emitter.sentIds.get(249)).isEqualTo("250");
        assertThat(boundary).isEqualTo(250L);
    }

    private static Map<String, Object> envelope(long sequence, String type, String elementId) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("sequence", sequence);
        e.put("id", UUID.randomUUID().toString());
        e.put("type", type);
        e.put("version", 1);
        e.put("occurredAt", Instant.now().toString());
        if (elementId != null) e.put("elementId", elementId);
        e.put("data", Map.of());
        return e;
    }
}
