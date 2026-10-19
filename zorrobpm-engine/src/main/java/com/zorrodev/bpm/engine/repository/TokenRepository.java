package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TokenRepository extends JpaRepository<TokenEntity, UUID> {

    /**
     * WO-REL-40 (B-5): row-locked read of the token for the atomic
     * pending-branches decrement. The {@code SELECT ... FOR UPDATE} serialises
     * concurrent {@code decrementPendingBranches} calls inside the caller's
     * transaction (same shape as {@code ActivityRepository.findByIdForUpdate},
     * WO-REL-30 B-3). A plain {@code findById} here is a lost-update race:
     * two branches read the same counter and both write back the same value.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TokenEntity t WHERE t.id = :id")
    Optional<TokenEntity> findByIdForUpdate(UUID id);

    /**
     * WO-AUDIT-12 (F-20): root-токены инстанса — токены без родителя и без
     * scope, по которым идут его activity-строки.
     *
     * <p>У токенов нет колонки process_instance_id (осознанно: токен не
     * принадлежит инстансу напрямую), поэтому связь идёт через activities.
     * Нужен, чтобы non-interrupting event sub-process создавал свой
     * branch-токен CHILD'ом корневого, а не новым корнем (см.
     * {@code EventTrigger.triggerEventSubprocess}).
     */
    @Query("SELECT DISTINCT t.id FROM TokenEntity t, ActivityEntity a WHERE a.token = t.id "
        + "AND a.processInstanceId = :processInstanceId AND t.parentId IS NULL "
        + "AND t.scopeActivityId IS NULL ORDER BY t.id")
    List<UUID> findRootTokenIdsByProcessInstanceId(UUID processInstanceId);
}
