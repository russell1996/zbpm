package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.dto.MessageStartSubscription;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен MessageSubscriptions.
 */
public interface MessageSubscriptionDbOperations {

    UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName);

    UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId);

    UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId, String correlationKey);

    UUID createEventSubprocessMessageSubscription(UUID processInstanceId, String messageName, String eventSubprocessId);

    void createMessageStartSubscription(String processKey, UUID processDefinitionId, String elementId, String messageName);

    void deleteMessageStartSubscriptionsByKey(String processKey);

    /**
     * WO-C8-35 (CR-09, ШАГ 2/B1): unconsumed message subscriptions of this instance — the caller
     * keeps only the boundary / event-sub-process forms (the ones that continue elsewhere).
     */
    void deleteMessageSubscriptionsByProcessInstanceId(UUID processInstanceId);

    List<MessageStartSubscription> findMessageStartSubscriptions(String messageName);

    /**
     * WO-OPS-39: бесключевая выборка подписок — ТОЛЬКО подписки БЕЗ ключа корреляции
     * (ключ не задан или пуст).
     *
     * <p>Основа изоляции корреляции: бесключевая публикация не должна будить подписку,
     * у которой ключ задан (Zeebe 8.5: публикация без ключа не коррелирует ни с одной
     * ключевой подпиской — живой замер WO-OPS-28 Фаза 1, §7 Ф1-1). Подписки с ключом
     * берёт {@link #findMessageSubscriptionsByKey(String, String, UUID)}.</p>
     *
     * <p>WO-REL-31 CR-3: keyset-paged — до {@code FAN_OUT_BATCH_SIZE} (500) строк в
     * детерминированном порядке id-DESC. Cursor = минимальный id предыдущей страницы
     * (null = первая страница).</p>
     */
    List<MessageSubscription> findKeylessMessageSubscriptions(String messageName, UUID processInstanceId, UUID cursorId);

    List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey);

    /** Keyset-paged variant — batch ≤500, id-DESC order, cursor = previous page min id. */
    List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey, UUID cursorId);

    boolean consumeMessageSubscription(UUID subscriptionId);
}
