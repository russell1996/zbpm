package com.zorrodev.bpm.engine.trust;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ElementListenerPhaseRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * WO-AUDIT-18 (аудит F-14): наблюдатель модели доверия канала завершений.
 *
 * <p><b>Зачем.</b> Канал завершений общий: один direct-exchange
 * {@code zorrobpm.completions}, все воркеры публикуют в общий routing key, движок
 * принимает результат по {@code serviceTaskId} из тела, НЕ сверяя отправителя.
 * Раздача джобы идёт в очередь по ТИПУ джобы ({@code zorrobpm.jobs.<jobType>}) —
 * потребители конкурируют, и брокер публикатору НЕ сообщает, кто взял джобу; в
 * теле completion личности воркера тоже нет (ни поля, ни заголовка; брокер
 * проставляет {@code user_id} только если клиент выставил его сам — Java-клиент не
 * выставляет). Поэтому «completion от воркера, не получавшего задачу»
 * атрибутировать НЕЛЬЗЯ — и этот монитор решает ВЫЧИСЛИМУЮ часть: ловит
 * completion, которая не соответствует НИ ОДНОЙ задаче, выданной воркеру.
 *
 * <p><b>Что считается аномалией.</b>
 * <ul>
 *   <li>{@code unknown_task} — движок никогда не создавал задачу с таким id;</li>
 *   <li>{@code no_job_issued} — задача создана, но джобу под неё воркеру НЕ
 *       выдавали: нет строки {@code service_tasks} (она и есть запись о выдаче
 *       джобы) и нет открытой listener-фазы (WO-C8-21/24/28 — под user task
 *       выдаётся listener-джоба, её id — это id активности).</li>
 * </ul>
 *
 * <p><b>Чего монитор НЕ делает.</b> Он НЕ отклоняет completion и НЕ меняет
 * семантику приёма: это наблюдательность, а не новая авторизация — её введение
 * меняло бы протокол/контракт (G-C). Решение о модели доверия канала завершений —
 * за maintainer ({@code docs/internal}, F-14).
 *
 * <p><b>Стоимость.</b> Два PK-чтения на completion (активность + service_tasks) в
 * штатном случае и два (активность + phase) для неизвестного id. Ни одного
 * лишнего SELECT'а на горячем пути: обе проверки — по первичному ключу.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompletionTrustMonitor {

    /** Completion по id, которого движок никогда не создавал. */
    public static final String REASON_UNKNOWN_TASK = "unknown_task";

    /** Completion по задаче, под которую джобу воркеру не выдавали. */
    public static final String REASON_NO_JOB_ISSUED = "no_job_issued";

    private final ActivityRepository activityRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final ElementListenerPhaseRepository phaseRepository;
    private final BpmMetrics bpmMetrics;

    /**
     * Осматривает completion и ЗАПИСЫВАЕТ аномалию (счётчик + WARN-лог).
     *
     * @return причина аномалии (тег счётчика) либо {@code null}, если completion
     *         соответствует задаче, выданной воркеру
     */
    public String inspect(UUID serviceTaskId) {
        if (serviceTaskId == null) {
            return record(REASON_UNKNOWN_TASK, serviceTaskId, "completion без serviceTaskId");
        }
        Optional<ActivityEntity> activity = activityRepository.findById(serviceTaskId);
        if (activity.isEmpty()) {
            // activity-строки нет у фазовой джобы (WO-C8-25) — это легально.
            if (phaseRepository.existsById(serviceTaskId)) {
                return null;
            }
            return record(REASON_UNKNOWN_TASK, serviceTaskId,
                "движок никогда не создавал задачу с таким id");
        }
        // Строка service_tasks — это и есть запись движка о том, что джоба выдана.
        if (serviceTaskRepository.existsById(serviceTaskId)) {
            return null;
        }
        // Listener-джоба под user task выдаётся с id'ом САМОЙ активности (WO-C8-21/24/28):
        // пока фаза открыта, id занят легальным worker-job'ом.
        if (hasOpenListenerPhase(activity.get())) {
            return null;
        }
        return record(REASON_NO_JOB_ISSUED, serviceTaskId,
            "у задачи нет строки service_tasks и открытой listener-фазы — джобу под неё не выдавали");
    }

    /** Открыта ли хоть одна из listener-фаз активности (индексы живут на строке activities). */
    private static boolean hasOpenListenerPhase(ActivityEntity activity) {
        return activity.getPendingCreatingListenerIndex() != null
            || activity.getPendingCompletingListenerIndex() != null
            || activity.getPendingAssigningListenerIndex() != null
            || activity.getPendingUpdatingListenerIndex() != null
            || activity.getPendingCancelingListenerIndex() != null;
    }

    /** Счётчик + WARN. Исход completion'а НЕ меняется — это наблюдение, а не запрет. */
    private String record(String reason, UUID serviceTaskId, String detail) {
        bpmMetrics.completionUnissued(reason);
        log.warn("WO-AUDIT-18: completion не соответствует выданной воркеру задаче — "
                + "serviceTaskId={}, reason={} ({})", serviceTaskId, reason, detail);
        return reason;
    }
}
