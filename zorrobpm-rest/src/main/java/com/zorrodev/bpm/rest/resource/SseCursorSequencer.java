package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.service.EventQueryService;
import com.zorrodev.bpm.exchange.TraceHeaders;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * WO-AUDIT-9 (шаг 5b): чтение ленты / курсор / водяной знак — бывший
 * сиквенсор {@code SseEventStreamService} (WO-REL-38, WO-REL-52 A3, WO-REL-55,
 * WO-REL-56 part A + NEW3-02/NEW3-03, WO-REL-58, WO-OBS-8), перенесённый
 * построчно, без смены семантики.
 *
 * <p>Механика (см. javadoc методов — сохранён дословно): каждое событие нашего
 * ряда при прибытии встаёт в CACHED min-heap по курсору и выпускается только
 * когда водяной знак дошёл до него; неразрешённое событие НЕ держит
 * consumer-поток и НЕ держит выпуск меньших позиций за собой; старые повторы
 * уже выпущенных позиций отбрасываются; gap-таймер признаёт дырой конкретную
 * позицию в heap'е по бюджету defer.
 *
 * <p>Зависимости — constructor-injected: {@link EventQueryService} (позиции),
 * {@link SseDeliveryDispatcher} (выпуск), реестр (gap-close対象 — только LIVE),
 * планировщик defer/gap (retry-lane фасада), метрики (nullable — unit-харнессы),
 * delay-suppliers (test-shrinkable volatile-поля фасада). Рассылка
 * ({@code dispatchDeferred} → диспетчер) идёт ПОД сиквенсор-локом: порядок
 * выпуска = порядок рассылки. Порядок локов везде sequencer→session
 * (обратного пути нет: writer/pump сиквенсор не трогают) — инверсии нет.
 */
@Slf4j
public final class SseCursorSequencer {

    /** Gap-close действие над LIVE-сессиями (фасад). */
    public interface GapCloser {
        void closeLiveSessionsForGap(long fromCursor, long toCursor);
    }

    /**
     * Defer/gap планирование на bounded retry-lane фасада: сам планировщик и
     * test-shrinkable задержка defer-бюджета. Одним холдером (а не двумя
     * suppliers), чтобы класс держал целевой лимит полей WO-AUDIT-9.
     */
    public record DeferScheduling(Supplier<ScheduledExecutorService> scheduler,
            Supplier<Long> delayMs) {
    }

    private final EventQueryService eventQueryService;
    private final SseDeliveryDispatcher deliveryDispatcher;
    private final GapCloser gapCloser;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final BpmMetrics bpmMetrics;
    private final DeferScheduling deferScheduling;

    public SseCursorSequencer(EventQueryService eventQueryService,
            SseDeliveryDispatcher deliveryDispatcher,
            GapCloser gapCloser,
            tools.jackson.databind.ObjectMapper objectMapper,
            BpmMetrics bpmMetrics,
            DeferScheduling deferScheduling) {
        this.eventQueryService = eventQueryService;
        this.deliveryDispatcher = deliveryDispatcher;
        this.gapCloser = gapCloser;
        this.objectMapper = objectMapper;
        this.bpmMetrics = bpmMetrics;
        this.deferScheduling = deferScheduling;
    }

    /**
     * Приём события с моста (consumer-поток или прямой вызов): парсинг,
     * pdUuid-резолвинг, MDC-окно, live-курсор → либо немедленный stage +
     * выпуск, либо defer, либо отброс чужого sequence.
     */
    public void accept(String messageBody, Map<String, ?> amqpHeaders) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(messageBody, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse domain event envelope", e);
            return;
        }

        String processInstanceId = (String) envelope.get("processInstanceId");
        String processDefinitionId = (String) envelope.get("processDefinitionId");
        Object sequenceObj = envelope.get("sequence");
        long sequence = sequenceObj instanceof Number n ? n.longValue() : 0;

        // WO-PERF-6: hoist UUID.fromString outside the per-client loop.
        // WO-REL-52 (verifier HOLD-1): резолвинг ОДИН на оба пути (live и
        // deferred, см. resolveEventPdUuid) — невалидный UUID fail-closed
        // в обоих, валидный доставляется обоим.
        UUID pdUuid = resolveEventPdUuid(processDefinitionId);
        if (processDefinitionId != null && pdUuid == null) {
            // fail-closed: no client matches an unparsable pdId
            return;
        }

        // WO-OBS-8: MDC for the fan-out below (SSE is the fifth WO point: HTTP, worker,
        // completion, outbox, SSE). PI prefers the header (no parse cost), falls back to
        // the envelope; traceId comes only from the W3C header. Save/restore: the bridge
        // thread is a shared consumer thread, never leak one event's MDC into the next.
        String priorTraceId = org.slf4j.MDC.get(TraceHeaders.MDC_TRACE_ID);
        String priorPi = org.slf4j.MDC.get(TraceHeaders.MDC_PROCESS_INSTANCE_ID);
        if (amqpHeaders != null) {
            Object piHeader = amqpHeaders.get(TraceHeaders.PROCESS_INSTANCE_ID_HEADER);
            if (piHeader != null) {
                processInstanceId = piHeader.toString();
            }
            Object tpHeader = amqpHeaders.get(TraceHeaders.TRACE_PARENT_HEADER);
            String headerTraceId = tpHeader != null
                ? TraceHeaders.extractTraceId(tpHeader.toString()) : null;
            if (headerTraceId != null) {
                org.slf4j.MDC.put(TraceHeaders.MDC_TRACE_ID, headerTraceId);
            }
        }
        if (processInstanceId != null) {
            org.slf4j.MDC.put(TraceHeaders.MDC_PROCESS_INSTANCE_ID, processInstanceId);
        }
        // WO-REL-38: live идёт в рассылку только с назначенной позицией —
        // иначе клиентский курсор (SSE id) указывал бы на sequence, который
        // может навсегда пропустить событие задержанной транзакции (F15).
        // WO-REL-52 (NEW-03, A3): ожидание позиции НЕ спит в consumer-потоке
        // моста (старые 30×sleep(100) сериализовали ВЕСЬ мост за одним
        // медленным событием). Позиция есть сразу — рассылаем; нет, но ряд
        // существует — откладываем событие в retry-lane (тот же bounded
        // retry-механизм REL-47, не новый пул): consumer-поток свободен для
        // следующих событий, отложенное рассылается позже с той же семантикой
        // (позиция/отброс/лог — в dispatchDeferred, построчно та же). Ряда
        // нет вообще — отброс сразу (ждать нечего), как раньше.
        Long cursor = resolveLiveCursor(sequence);
        if (cursor == null) {
            if (eventQueryService.eventSequenceExists(sequence)) {
                long ticket = stageSequencedEvent(messageBody, amqpHeaders, sequence, null);
                deferUnpositioned(messageBody, amqpHeaders, sequence, ticket, 1);
            } else {
                // WO-REL-56 (part B, NEW3-03): чужой sequence (нет строки в
                // ЭТОЙ БД — другая инсталляция на том же брокере/vhost,
                // тестовая публикация) — отброс, как было до WO-REL-55
                // ("unknown sequence skipped" на 9ba3be16). Рассылка с
                // cursor = sequence смешивала домены: клиент получал SSE id
                // из чужого домена, и его Last-Event-ID перескакивал не на
                // ту точку (пропуск/дубль на reconnect; для sequence=0 —
                // утечка чужих событий SUPER_ADMIN-клиентам, чей фильтр
                // pdId null). Отброс видимый: счётчик, не тихий warn.
                log.warn("SSE live event with unknown sequence {} skipped (no such row in this DB)", sequence);
                if (bpmMetrics != null) {
                    bpmMetrics.sseForeignSequenceDropped();
                }
            }
            return;
        }
        try {
            stageSequencedEvent(messageBody, amqpHeaders, sequence, cursor);
            markSequencedResolved(sequence, cursor);
            releaseSequencedReady();
        } finally {
            restoreMdc(TraceHeaders.MDC_TRACE_ID, priorTraceId);
            restoreMdc(TraceHeaders.MDC_PROCESS_INSTANCE_ID, priorPi);
        }
    }

    private static void restoreMdc(String key, String prior) {
        if (prior == null) {
            org.slf4j.MDC.remove(key);
        } else {
            org.slf4j.MDC.put(key, prior);
        }
    }

    /**
     * WO-REL-38: live-курсор — commit-ordered {@code feed_position}, НЕ raw
     * {@code sequence} из тела. Строка гарантированно закоммичена (мост читает
     * её из закоммиченного брокерного сообщения), но позиция может быть ещё не
     * назначена.
     *
     * <p>WO-REL-52 (A3): этот метод НЕ ждёт — один неблокирующий read. Ожидание
     * переехало в {@link #deferUnpositioned} (retry-lane, вне consumer-потока):
     * спать 30×100мс в единственном consumer'е моста означало сериализовать
     * весь live-поток за одним медленным событием. Неизвестный sequence
     * (чужой id, не наша строка) — null сразу, без ожидания: ждать нечего.
     * Пропуск здесь — не потеря навсегда: catchup читает то же fp-окно от
     * курсора клиента, и событие доберётся при следующем reconnect (а для
     * существующего ряда — через отложенную рассылку, см. ниже).
     *
     * @return позиция курсора или null (позиции пока нет / ряда нет)
     */
    private Long resolveLiveCursor(long sequence) {
        return eventQueryService.resolveFeedPositionBySequence(sequence).orElse(null);
    }

    /**
     * WO-REL-52 (A3): отложенная рассылка события, чья позиция ещё не
     * назначена. Тот же retry-lane REL-47 (один daemon-поток, bounded):
     * каждая попытка — один неблокирующий read позиции; позиция появилась —
     * рассылка тем же путём, что live (envelope уже разобран? нет — тело
     * хранится сырым и разбирается заново в dispatchDeferred, чтобы MDC и
     * envelope-путь были теми же, построчно); бюджет попыток исчерпан —
     * тот же warn + пропуск, что раньше после 30×100мс (catchup вылечит при
     * reconnect). Отложенное событие НЕ блокирует consumer-поток: он вернулся
     * сразу после schedule.
     */
    private static final int DEFERRED_CURSOR_ATTEMPTS = 30;

    /**
     * WO-REL-56 (part A, NEW3-02): выпуск по ПОЗИЦИИ с водяным знаком.
     *
     * <p>Проблема WO-REL-55: arrival-FIFO выпускал события в порядке ПРИБЫТИЯ
     * (outbox `ORDER BY created_at` — момент emit, Java-часы), а gap-детектор
     * сравнивал по feed-ПОЗИЦИИ (порядок commit, `FeedPositionAssigner`).
     * Порядок emit ≠ порядок commit — штатная ситуация под конкурентной
     * нагрузкой (T1 emit раньше / commit позже, T2 наоборот): прибытие
     * pos 9 → 11 → 10 детектор читал как "дыру" между 9 и 11 и закрывал ВСЕХ
     * LIVE-клиентов, а 11 всё равно уходило раньше 10 (переупорядочивание не
     * устранено + реконнект-шторм × число клиентов на каждый такой случай).
     *
     * <p>Механика: каждое событие нашего ряда при прибытии встаёт в CACHED
     * min-heap по курсору (`stageSequencedEvent` кэширует также тело для
     * отложенного выпуска) и выпускается только когда водяной знак дошёл до
     * него: позиция N выпускается, когда все позиции ≤ N либо разрешились,
     * либо признаны дырой по таймауту (бюджет попыток defer, как раньше —
     * только теперь дырой признаётся КОНКРЕТНАЯ позиция в heap'е, а не голова
     * FIFO). Неразрешённое событие НЕ держит consumer-поток (как раньше) и
     * НЕ держит выпуск меньших/уже-разрешённых позиций за собой — в отличие
     * от FIFO-головы: разрешённое 10 выпускается сразу, даже если 11 пришло
     * раньше, а выпуск 11 ждёт разрешения 10 или её таймаута.
     *
     * <p>Порядок прибытия ≠ порядок выпуска осознанно: наблюдаемый поток —
     * порядок commit (позиции), в котором assigner ставит feed_position; это
     * и есть порядок, по которому catchup читает окно и по которому клиент
     * хранит Last-Event-ID. Сценарий REL-55 A1 (медленная голова, быстрый
     * хвост) сохраняется: медленное событие всё ещё не выпускает быстрый
     * хвост раньше себя — но теперь по ПОЗИЦИИ, а не по билету прибытия.
     *
     * <p>Старые (stale) повторы уже выпущенных позиций (retry outbox,
     * reconnect-переигрывания: водяной знак уже выше) — не ждут и не
     * выпускаются повторно, а отбрасываются молча: их данные клиент уже
     * получил, повторный выпуск дал бы дубль в потоке.
     *
     * <p>Потоки: сиквенсор трогают consumer-поток моста и retry-lane
     * (defer-колбэки) — всё состояние под `sequencerLock`. Рассылка
     * (`dispatchDeferred` → диспетчер) идёт ПОД тем же локом:
     * порядок выпуска = порядок рассылки, без окна между ними. Порядок
     * локов везде sequencer→session (обратного пути нет: writer/pump
     * сиквенсор не трогают) — инверсии нет. `closeRevokedClient` под локом
     * безопасен: снятие состояния не берёт глобальных локов,
     * `emitter.complete()` — без I/O.
     */
    private final Object sequencerLock = new Object();
    /**
     * CACHED min-heap по курсору (позиции); голова — наименьшая
     * неразрешённая/невыпущенная позиция. CACHED, потому что heap по курсору
     * без тела не может выпустить событие, чья позиция разрешилась позже
     * прибытия (defer-колбэк отмечает позицию — тело обязано ждать в heap'е,
     * consumer-поток при этом свободен, как раньше).
     */
    private final java.util.PriorityQueue<SequencedEvent> sequencer =
        new java.util.PriorityQueue<>(java.util.Comparator.comparingLong((SequencedEvent e) -> e.cursor)
            .thenComparingLong(e -> e.ticket));
    private long nextSequencerTicket = 0;
    /**
     * Водяной знак выпуска: все позиции ≤ watermark либо выпущены, либо
     * признаны дырой. Следующая к выпуску позиция — ровно watermark + 1.
     * -1 — выпусков ещё не было, первое событие дырой не считается.
     */
    private long dispatchWatermark = -1;
    /**
     * Максимальный рассыльный курсор (для детекции дыры части B).
     * Null — рассылок ещё не было, первое событие дырой не считается.
     */
    private Long maxDispatchedCursor = null;

    /** Одна запись сиквенсора: событие + разрешённая позиция. */
    private static final class SequencedEvent {
        final long ticket;
        final String messageBody;
        final Map<String, Object> headers;
        final long sequence;
        final long cursor;

        SequencedEvent(long ticket, String messageBody, Map<String, Object> headers,
                long sequence, long cursor) {
            this.ticket = ticket;
            this.messageBody = messageBody;
            this.headers = headers;
            this.sequence = sequence;
            this.cursor = cursor;
        }
    }

    /**
     * Поставить событие в heap. Копия заголовков — та же причина, что
     * в defer: исходный map принадлежит listener-контейнеру.
     *
     * @param cursorOrNull уже разрешённая позиция (live-путь) или null
     *     (defer-путь: позиция ещё неизвестна, запись ждёт разрешения;
     *     отдельный DB-read здесь не делаем — вызывающий только что читал)
     * @return билет записи (для mark/mark-hole из defer-колбэков)
     */
    private long stageSequencedEvent(String messageBody, Map<String, ?> amqpHeaders,
            long sequence, Long cursorOrNull) {
        Map<String, Object> headersCopy = amqpHeaders == null ? null
            : new java.util.LinkedHashMap<>(amqpHeaders);
        synchronized (sequencerLock) {
            long ticket = nextSequencerTicket++;
            sequencer.add(new SequencedEvent(ticket, messageBody, headersCopy, sequence,
                cursorOrNull == null ? Long.MIN_VALUE : cursorOrNull));
            return ticket;
        }
    }

    /** Позиция разрешилась (live или defer-колбэк) — отметить и выпустить готовое. */
    private void markSequencedResolved(long sequence, long cursor) {
        synchronized (sequencerLock) {
            SequencedEvent found = null;
            for (SequencedEvent e : sequencer) {
                if (e.sequence == sequence && e.cursor == Long.MIN_VALUE) {
                    found = e;
                    break;
                }
            }
            if (found != null) {
                sequencer.remove(found);
                sequencer.add(new SequencedEvent(found.ticket, found.messageBody, found.headers,
                    found.sequence, cursor));
            }
        }
    }

    /**
     * Бюджет исчерпан / ряда нет — признать позицию дырой: удалить запись из
     * heap'а и двинуть водяной знак через неё, чтобы выпуск пошёл дальше.
     */
    private void markSequencedHole(long ticket) {
        synchronized (sequencerLock) {
            SequencedEvent found = null;
            for (SequencedEvent e : sequencer) {
                if (e.ticket == ticket) {
                    found = e;
                    break;
                }
            }
            if (found != null) {
                sequencer.remove(found);
                if (found.cursor != Long.MIN_VALUE && found.cursor == dispatchWatermark + 1) {
                    dispatchWatermark = found.cursor;
                }
            }
        }
    }

    /**
     * Правила выпуска головы (под сиквенсор-локом, рассылка там же —
     * порядок выпуска = порядок рассылки):
     * <ul>
     *   <li>bootstrap: ничего ещё не рассылалось — водяной знак прыгает к
     *       голове без gap-close (клиент не может пропустить то, что никогда
     *       не отправлялось; стартовые курсоры произвольны — позиции в БД
     *       не начинаются с нуля);</li>
     *   <li>голова == watermark + 1 — штатный выпуск;</li>
     *   <li>голова ≤ watermark — stale-повтор уже выпущенного (retry outbox,
     *       переигрывание): выпускаем повторно, как раньше (REL-55 тоже
     *       перевыпускал всё поставленное; gap-детектор на неё не стреляет —
     *       курсор не превышает максимум);</li>
     *   <li>голова > watermark + 1 и разрешена — ЖДЁМ (не закрываем!): меньшая
     *       позиция могла просто прибыть позже (порядок emit ≠ порядок commit —
     *       NEW3-02). Одноразовый gap-таймер на бюджет defer признает её дырой,
     *       только если она не пришла за бюджет; тогда — gap-close + выпуск.</li>
     * </ul>
     *
     * <p>Ложная дыра NEW3-02 сюда не попадает структурно: прибытие 11 раньше
     * 10 (обе разрешены) — это разрешённая голова с разрывом, выпуск ждёт
     * прихода 10 или gap-таймаута, gap-детектор не вызывается. Детектор видит
     * только разрешённые головы, меньшие которых уже либо выпущены, либо
     * признаны дырой по таймауту.
     *
     * <p>WO-REL-55: решение о выпуске под сиквенсор-локом НЕДОСТАТОЧНО —
     * consumer-поток и retry-lane конкурентны, и live-разрешение seq 2
     * успевает раньше defer-разрешения seq 1. Поэтому `releaseSequencedReady`
     * вызывается и из live-пути, и из defer-колбэка: кто бы ни разрешил
     * позицию, выпуск идёт строго по водяному знаку. Defer-колбэк,
     * разрешивший НЕ голову, только отмечает позицию — рассылку сделает тот,
     * кто разрешит голову. Это и есть упорядочивающий механизм (не блокировка
     * потока, а блокировка ВЫПУСКА).
     */
    private void releaseSequencedReady() {
        java.util.List<SequencedEvent> ready = new java.util.ArrayList<>();
        synchronized (sequencerLock) {
            while (!sequencer.isEmpty()) {
                SequencedEvent head = sequencer.peek();
                if (head.cursor == Long.MIN_VALUE) {
                    break;
                }
                if (maxDispatchedCursor == null) {
                    // Bootstrap (см. javadoc выше): первый выпуск без дыры.
                    dispatchWatermark = head.cursor - 1;
                } else if (head.cursor > dispatchWatermark + 1) {
                    // Разрыв вперёд — ждём меньшую позицию (NEW3-02), а не
                    // закрываем: планируем одноразовый gap-таймер на бюджет
                    // defer, если его ещё нет.
                    armGapTimerLocked();
                    break;
                }
                ready.add(sequencer.poll());
            }
            // Рассылка — под тем же локом: порядок выпуска = порядок рассылки
            // (см. javadoc сиквенсора про порядок локов).
            for (SequencedEvent e : ready) {
                dispatchDeferred(e.messageBody, e.headers, e.sequence, e.cursor);
                maxDispatchedCursor = e.cursor;
                if (e.cursor > dispatchWatermark) {
                    dispatchWatermark = e.cursor;
                }
            }
            if (sequencer.isEmpty()) {
                cancelGapTimerLocked();
            }
        }
    }

    /**
     * WO-REL-56 (part A): одноразовый gap-таймер. Разрешённая голова с
     * разрывом ждёт меньшую позицию не дольше бюджета defer (те же 30 попыток
     * × deferredCursorDelayMs — shrink через тот же volatile-supplier, что REL-55
     * A1). Срабатывание признаёт дырой ВСЁ до минимальной разрешённой головы
     * (младшие неразрешённые к тому моменту уже признаны дырой своим
     * defer-циклом либо всё ещё в бюджете — но раз голова разрешена и ждёт,
     * младшая либо придёт своим defer, либо её hole двинет знак; двойного
     * учёта нет: hole двигает знак только через свою позицию).
     *
     * <p>Один таймер на heap (не на событие): повторные разрывы, пока таймер
     * взведён, нового не планируют. Срабатывание — no-op, если разрыв уже
     * закрылся приходом (знак уже ≥).
     */
    private java.util.concurrent.ScheduledFuture<?> gapTimerFuture = null;

    private void armGapTimerLocked() {
        if (gapTimerFuture != null && !gapTimerFuture.isDone()) {
            return;
        }
        ScheduledExecutorService lane;
        try {
            lane = deferScheduling.scheduler().get();
        } catch (java.util.concurrent.RejectedExecutionException re) {
            // Lane насыщена — ждать негде: деградация к поведению REL-55
            // (немедленный gap-close), с явным warn, не тихая.
            SequencedEvent head = sequencer.peek();
            log.warn("SSE gap-timer lane saturated, closing LIVE clients immediately for gap "
                + "(head cursor {}, watermark {})", head == null ? -1 : head.cursor, dispatchWatermark);
            if (head != null && maxDispatchedCursor != null) {
                gapCloser.closeLiveSessionsForGap(maxDispatchedCursor, head.cursor);
                dispatchWatermark = head.cursor - 1;
                releaseSequencedReady();
            }
            return;
        }
        long budgetMs = (long) DEFERRED_CURSOR_ATTEMPTS * deferScheduling.delayMs().get();
        gapTimerFuture = lane.schedule(() -> {
            synchronized (sequencerLock) {
                gapTimerFuture = null;
                SequencedEvent head = sequencer.peek();
                if (head == null || head.cursor == Long.MIN_VALUE) {
                    // Разрыв уже закрылся приходом (знак двинулся) либо голова
                    // всё ещё разрешается своим defer-циклом — он сам двинет
                    // знак (разрешение/дыра) и вызовет выпуск. No-op.
                    return;
                }
                if (maxDispatchedCursor != null && head.cursor > dispatchWatermark + 1) {
                    // Позиция пропущена дольше бюджета — РЕАЛЬНАЯ дыра:
                    // закрываем LIVE до рассылки детектора (Last-Event-ID
                    // закрытых остаётся на последнем доставленном, catchup на
                    // reconnect забирает дыру честно), затем выпускаем голову
                    // и непрерывную цепочку за ней; второй разрыв (если есть)
                    // ждёт заново через обычный arm в release.
                    gapCloser.closeLiveSessionsForGap(maxDispatchedCursor, head.cursor);
                    // WO-REL-58: знак — за дыру ДО входа в цикл (зеркало
                    // saturated-ветки выше): иначе первая же итерация видит ту
                    // же голову (h.cursor > dispatchWatermark + 1), break'ится
                    // немедленно, знак не двигается, и releaseSequencedReady
                    // взводит таймер заново бесконечно — realtime мёртв для всех
                    // до рестарта JVM после первой реальной дыры.
                    dispatchWatermark = head.cursor - 1;
                    while (!sequencer.isEmpty()) {
                        SequencedEvent h = sequencer.peek();
                        if (h.cursor == Long.MIN_VALUE) {
                            break;
                        }
                        if (h.cursor > dispatchWatermark + 1) {
                            break;
                        }
                        sequencer.poll();
                        dispatchDeferred(h.messageBody, h.headers, h.sequence, h.cursor);
                        maxDispatchedCursor = h.cursor;
                        if (h.cursor > dispatchWatermark) {
                            dispatchWatermark = h.cursor;
                        }
                    }
                }
                // Добивка: остаток heap'а (второй разрыв, неразрешённая голова)
                // — либо выпустить, либо взвести таймер заново. Reentrant
                // (тот же поток уже держит sequencerLock) — безопасно.
                releaseSequencedReady();
            }
        }, budgetMs, TimeUnit.MILLISECONDS);
    }

    private void cancelGapTimerLocked() {
        if (gapTimerFuture != null) {
            gapTimerFuture.cancel(false);
            gapTimerFuture = null;
        }
    }

    /** Отмена gap-таймера при shutdown (под сиквенсор-локом вызывающего). */
    public void cancelGapTimer() {
        synchronized (sequencerLock) {
            cancelGapTimerLocked();
        }
    }

    private void deferUnpositioned(String messageBody, Map<String, ?> amqpHeaders,
            long sequence, long ticket, int attempt) {
        ScheduledExecutorService lane;
        try {
            lane = deferScheduling.scheduler().get();
        } catch (java.util.concurrent.RejectedExecutionException re) {
            log.warn("SSE deferred dispatch saturated, dropping unpositioned sequence {} (catchup will heal)", sequence);
            return;
        }
        // Копия заголовков: исходный map принадлежит listener-контейнеру и
        // может быть переиспользован; MDC ставится заново в dispatchDeferred.
        Map<String, Object> headersCopy = amqpHeaders == null ? null
            : new java.util.LinkedHashMap<>(amqpHeaders);
        long delayMs = deferScheduling.delayMs().get();
        lane.schedule(() -> {
            Long cursor = resolveLiveCursor(sequence);
            if (cursor != null) {
                markSequencedResolved(sequence, cursor);
                releaseSequencedReady();
                return;
            }
            if (attempt >= DEFERRED_CURSOR_ATTEMPTS) {
                log.warn("SSE live event with sequence {} still has no feed position after ~{}ms, skipped (catchup will heal on reconnect)",
                    sequence, (long) DEFERRED_CURSOR_ATTEMPTS * deferScheduling.delayMs().get());
                markSequencedHole(ticket);
                releaseSequencedReady();
                return;
            }
            if (!eventQueryService.eventSequenceExists(sequence)) {
                log.warn("SSE live event with unknown sequence {} skipped (no such row)", sequence);
                markSequencedHole(ticket);
                releaseSequencedReady();
                return;
            }
            deferUnpositioned(messageBody, headersCopy, sequence, ticket, attempt + 1);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * WO-REL-52 (verifier HOLD-1): pdUuid-резолвинг, общий для live-пути
     * ({@code accept}) и deferred-пути ({@code dispatchDeferred}).
     * До фикса deferred-путь передавал pdUuid=null всегда — restricted-клиенты
     * (effective != null) тихо пропускали ВСЕ отложенные события, хотя
     * live-эквивалент доставлялся. Невалидный UUID — null + warn, вызывающий
     * роняет событие целиком (fail-closed, как раньше в live-пути).
     */
    private UUID resolveEventPdUuid(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        try {
            return UUID.fromString(processDefinitionId);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid processDefinitionId UUID {}", processDefinitionId);
            return null;
        }
    }

    /**
     * WO-REL-52: рассылка отложенного события — тот же путь, что live
     * (MDC-окно + envelope + cursor + диспетчер, построчно из accept),
     * только вызывается из retry-lane, а не из consumer-потока. Logger-строка
     * та же (SSE dispatch), чтобы грепы наблюдаемости не различали пути.
     */
    private void dispatchDeferred(String messageBody, Map<String, ?> amqpHeaders,
            long sequence, long cursor) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(messageBody, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse domain event envelope", e);
            return;
        }
        String eventType = (String) envelope.get("type");
        String processInstanceId = (String) envelope.get("processInstanceId");
        String processDefinitionId = (String) envelope.get("processDefinitionId");
        // WO-REL-52 (verifier HOLD-1): тот же pdUuid-резолвинг, что в live-пути —
        // restricted-клиенты получают отложенные события, а не тихий пропуск.
        UUID pdUuid = resolveEventPdUuid(processDefinitionId);
        if (processDefinitionId != null && pdUuid == null) {
            // fail-closed: same as the live path
            return;
        }
        String priorTraceId = org.slf4j.MDC.get(TraceHeaders.MDC_TRACE_ID);
        String priorPi = org.slf4j.MDC.get(TraceHeaders.MDC_PROCESS_INSTANCE_ID);
        if (amqpHeaders != null) {
            Object piHeader = amqpHeaders.get(TraceHeaders.PROCESS_INSTANCE_ID_HEADER);
            if (piHeader != null) {
                processInstanceId = piHeader.toString();
            }
            Object tpHeader = amqpHeaders.get(TraceHeaders.TRACE_PARENT_HEADER);
            String headerTraceId = tpHeader != null
                ? TraceHeaders.extractTraceId(tpHeader.toString()) : null;
            if (headerTraceId != null) {
                org.slf4j.MDC.put(TraceHeaders.MDC_TRACE_ID, headerTraceId);
            }
        }
        if (processInstanceId != null) {
            org.slf4j.MDC.put(TraceHeaders.MDC_PROCESS_INSTANCE_ID, processInstanceId);
        }
        try {
            envelope.put("feedPosition", cursor);
            deliveryDispatcher.dispatch(envelope, eventType, processInstanceId, pdUuid, cursor);
            log.info("SSE dispatch: type={}, processInstanceId={}, sequence={}, feedPosition={}",
                eventType, processInstanceId, sequence, cursor);
        } finally {
            restoreMdc(TraceHeaders.MDC_TRACE_ID, priorTraceId);
            restoreMdc(TraceHeaders.MDC_PROCESS_INSTANCE_ID, priorPi);
        }
    }

    /**
     * Тест-совместимость: длительность defer-бюджета в миллисекундах
     * (бывшее volatile-поле сервиса — теперь через supplier фасада).
     */
    long deferredBudgetMsForTest() {
        return (long) DEFERRED_CURSOR_ATTEMPTS * deferScheduling.delayMs().get();
    }
}
