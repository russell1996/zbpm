package com.zorrodev.bpm.engine.retention;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the retention job that cleans up terminal process instances.
 * Disabled by default (ttlDays=0) — enable by setting zorrobpm.engine.retention.ttl-days to a positive value.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "zorrobpm.engine.retention")
public class RetentionConfig {
    /** TTL in days. Terminal instances older than this are eligible for cleanup. 0 = disabled. */
    private int ttlDays = 0;

    /** Poll interval in milliseconds. */
    private long pollIntervalMs = 3600_000; // 1 hour

    /**
     * WO-PERF-8: batch size for deletion (max instances per poll). 25, not 100:
     * {@code RetentionBatchProcessor.deleteInstances} runs up to 12 DELETEs in one
     * transaction per batch, so 100 instances held row locks far longer than needed.
     * Smaller chunks = shorter transactions at the same throughput (the job loops
     * until no eligible rows remain).
     *
     * <p>WO-REL-54: тот же размер — окно claim+delete-пачки
     * ({@code claimAndDeleteBatch}): claim SELECT и все DELETEs одной пачки идут
     * в одной транзакции, так что пачка обязана оставаться короткой.
     */
    private int batchSize = 25;

    /**
     * WO-REL-54 (NEW-12): временной бюджет одного прохода job'а в миллисекундах.
     * Положительный — job проверяет дедлайн МЕЖДУ пачками и останавливается,
     * оставив прогресс (закоммиченные пачки) для следующего запуска
     * планировщика. Проверка только между пачками: начатая пачка всегда
     * коммитится целиком — частично закоммиченной пачки не бывает.
     *
     * <p>WO-QW-5 (NEW2-12): дефолт — 10 минут, не 0. Ноль (= без лимита до
     * пустого claim'а) оставлял один проход бесконечно долгим на раздутой
     * таблице: следующий тик планировщика накладывался на ещё бегущий проход
     * (перекрытие проходов + удержание пула). 10 минут при пачке 25 — с
     * запасом на нормальный дренаж, переросший бюджет — переносится на
     * следующий тик, прогресс не теряется (пачки коммитятся по ходу).
     */
    private long passBudgetMs = 600_000;

    /**
     * WO-AUDIT-7: TTL чистки таблицы {@code events} в днях. 0 = выключено
     * (поведение не меняется — дефолт). Положительный — проход удаляет
     * назначенные ({@code feed_position IS NOT NULL}) строки старше TTL,
     * кроме: строк выше минимального активного SSE-курсора
     * ({@code SseLiveCursorTracker} — транзитный пин против гонки
     * catchup-vs-retention), строк без позиции (джоб ещё не назначил —
     * consumer их не видел) и строки с максимальной позицией (якорь
     * монотонности счётчика {@code FeedPositionAssigner}).
     */
    private int eventsTtlDays = 0;

    /**
     * WO-AUDIT-7: TTL чистки таблицы {@code outbox} в днях. 0 = выключено
     * (дефолт). Положительный — проход удаляет терминальные записи старше
     * TTL: опубликованные ({@code published = true}, любым статусом) и
     * карантинные ({@code status = 'FAILED'}). Активные/ожидающие/
     * ретраящиеся ({@code published = false AND status = 'PENDING'}) не
     * трогаются ни при каком TTL — их может забрать только поллер.
     */
    private int outboxTtlDays = 0;

    /**
     * WO-AUDIT-7: dry-run новых проходов (events/outbox): только считает и
     * логирует «удалил бы N», удаляет 0 строк. На instance/submission/
     * orphan-проходы НЕ влияет (у них свой выключатель — их TTL): флаг —
     * preview именно новой чистки, а не мастер-рубильник всего retention.
     * Проход с включённым dry-run пишет WARN об этом ограничении, чтобы
     * оператор не читал «ничего не удалено» как «нечего удалять везде».
     */
    private boolean dryRun = false;

    /**
     * WO-AUDIT-7: пауза между батчами новых проходов в миллисекундах.
     * 0 = без паузы (дефолт). Ненулевая даёт пулу/репликам дышать на
     * раздутых таблицах; проверка дедлайна — всё равно между батчами.
     */
    private long batchPauseMs = 0;
}
