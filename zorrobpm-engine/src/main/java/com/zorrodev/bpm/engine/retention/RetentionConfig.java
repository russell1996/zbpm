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
}
