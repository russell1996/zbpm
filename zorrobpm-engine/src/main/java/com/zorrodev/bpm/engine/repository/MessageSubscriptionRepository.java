package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MessageSubscriptionRepository extends JpaRepository<MessageSubscriptionEntity, UUID>, JpaSpecificationExecutor<MessageSubscriptionEntity> {

    List<MessageSubscriptionEntity> findByConsumedFalseAndMessageNameAndCorrelationKey(String messageName, String correlationKey);

    // WO-REL-31 CR-3: keyset-paged fan-out finders — batch ≤500, deterministic id-DESC order.
    // Cursor is the minimum id from the previous page (null = first page). Pages of non-interrupting
    // event-subprocess subscriptions are never revisited (ids from earlier pages are always > cursor),
    // so consumed=false re-firers don't create infinite loops.
    //
    // WO-OPS-39: выборки, ИГНОРИРУЮЩИЕ ключ корреляции, с пути корреляции убраны
    // (бесключевая публикация будит только бесключевые подписки). Остался один
    // key-ignoring finder — он нужен только тесту (EventSubProcessInclusiveJoinIntegrationTests)
    // и НА ПУТИ КОРРЕЛЯЦИИ НЕ ИСПОЛЬЗУЕТСЯ.

    /**
     * key-ignoring finder: возвращает подписки по имени сообщения и инстансу, КЛЮЧ
     * КОРРЕЛЯЦИИ НЕ УЧИТЫВАЕТ. На пути корреляции не использовать (WO-OPS-39) — для
     * бесключевой публикации есть
     * {@link com.zorrodev.bpm.engine.service.db.MessageSubscriptionDbOperations#findKeylessMessageSubscriptions}.
     */
    List<MessageSubscriptionEntity> findFirst500ByConsumedFalseAndMessageNameAndProcessInstanceIdOrderByIdDesc(String messageName, UUID processInstanceId);

    List<MessageSubscriptionEntity> findFirst500ByConsumedFalseAndMessageNameAndCorrelationKeyOrderByIdDesc(String messageName, String correlationKey);

    List<MessageSubscriptionEntity> findFirst500ByConsumedFalseAndMessageNameAndCorrelationKeyAndIdLessThanOrderByIdDesc(String messageName, String correlationKey, UUID id);

    @Modifying
    @Query("UPDATE MessageSubscriptionEntity e SET e.consumed = true WHERE e.id = :id AND e.consumed = false")
    int markConsumed(@Param("id") UUID id);

    static Specification<MessageSubscriptionEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, cb) -> cb.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<MessageSubscriptionEntity> byConsumed(boolean consumed) {
        return (root, query, cb) -> cb.equal(root.get("consumed"), consumed);
    }

    /**
     * WO-OPS-39: подписка БЕЗ ключа корреляции — ключ не задан ({@code null}) или пуст.
     *
     * <p>Бесключевая публикация будит ТОЛЬКО такие подписки: подписка с заданным
     * ключом ждёт публикацию с ТЕМ ЖЕ ключом (Zeebe 8.5: публикация без ключа не
     * коррелирует ни с одной ключевой подпиской — живой замер WO-OPS-28 Фаза 1,
     * §7 Ф1-1).</p>
     */
    static Specification<MessageSubscriptionEntity> byKeylessCorrelationKey() {
        return (root, query, cb) -> cb.or(
            cb.isNull(root.get("correlationKey")),
            cb.equal(root.get("correlationKey"), ""));
    }


    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM MessageSubscriptionEntity m WHERE m.processInstanceId = :processInstanceId")
    void deleteByProcessInstanceId(@org.springframework.data.repository.query.Param("processInstanceId") UUID processInstanceId);

    /**
     * WO-C8-37 (C37-3): consumes every unconsumed subscription bound to a finished
     * activity (host row COMPLETED/CANCELLED — the wait is over, its message can
     * never be claimed by the dead host again). Consumed rows stay untouched
     * (history); other activities' rows stay live.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE MessageSubscriptionEntity m SET m.consumed = true "
        + "WHERE m.processInstanceId = :processInstanceId AND m.consumed = false AND m.activityId IN :activityIds")
    int consumeByActivityIds(@org.springframework.data.repository.query.Param("processInstanceId") UUID processInstanceId,
        @org.springframework.data.repository.query.Param("activityIds") java.util.Collection<UUID> activityIds);
}
