package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ParallelGatewayEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ParallelGatewayRepository extends JpaRepository<ParallelGatewayEntity, UUID> {

    @Query("SELECT pg.enteredFlowId FROM ParallelGatewayEntity pg "
        + "WHERE pg.processInstanceId = :processInstanceId AND pg.gatewayElementId = :gatewayElementId "
        + "AND pg.expectedCount IS NULL")
    List<String> findEnteredFlows(UUID processInstanceId, String gatewayElementId);

    @Query("SELECT pg.expectedCount FROM ParallelGatewayEntity pg "
        + "WHERE pg.processInstanceId = :processInstanceId AND pg.gatewayElementId = :gatewayElementId "
        + "AND pg.expectedCount IS NOT NULL")
    List<Integer> findExpectedCounts(UUID processInstanceId, String gatewayElementId);

    boolean existsByProcessInstanceIdAndGatewayElementIdAndEnteredFlowId(
        UUID processInstanceId, String gatewayElementId, String enteredFlowId);

    /**
     * WO-C8-35 (CR-09, ШАГ 3/B2): element ids of the gateways in this instance that still hold
     * OPEN arrival rows — i.e. branches that arrived and are parked waiting for the join to fire.
     * Marker rows ({@code expectedCount} set) are excluded: they are not arrivals.
     */
    @Query("SELECT DISTINCT pg.gatewayElementId FROM ParallelGatewayEntity pg "
        + "WHERE pg.processInstanceId = :processInstanceId AND pg.expectedCount IS NULL")
    List<String> findGatewayIdsWithOpenArrivals(UUID processInstanceId);

    @Modifying
    void deleteByProcessInstanceIdAndGatewayElementId(UUID processInstanceId, String gatewayElementId);

    /**
     * WO-C8-38 (C38-2): scope-confined arrival cleanup — deletes arrival rows of the
     * given joins of one instance (e.g. joins lexically inside a cancelled scope, see
     * {@code ScopeContainment}). Marker rows ({@code expectedCount} set — MI/ad-hoc
     * bookkeeping) are excluded: they belong to their own handlers.
     */
    @Modifying
    @Query("DELETE FROM ParallelGatewayEntity pg "
        + "WHERE pg.processInstanceId = :processInstanceId "
        + "AND pg.gatewayElementId IN :gatewayElementIds AND pg.expectedCount IS NULL")
    void deleteArrivalsByProcessInstanceIdAndGatewayElementIds(UUID processInstanceId,
        java.util.Collection<String> gatewayElementIds);

    /**
     * WO-C8-38 (C38-2): whole-instance arrival cleanup — deletes arrival rows of the
     * instance (its joins will never fire: interrupting ESP replaced the flow,
     * operator cancelled the instance). Same marker-row exclusion as above.
     */
    @Modifying
    @Query("DELETE FROM ParallelGatewayEntity pg "
        + "WHERE pg.processInstanceId = :processInstanceId AND pg.expectedCount IS NULL")
    void deleteArrivalsByProcessInstanceId(UUID processInstanceId);

    /**
     * WO-C8-48: terminal-instance bookkeeping cleanup — deletes ALL rows of the instance,
     * arrivals AND marker rows (expectedCount set: MI batch counters, ad-hoc join keys,
     * inclusive expected counts). Called only from the two terminal transitions
     * (complete/cancel) of {@code ProcessInstanceDbOperations}: a finished instance
     * neither reads nor writes this table (it is transient bookkeeping, not history —
     * the process-execution trace and diagnostics do not read it, retention drops the
     * table wholesale for finished instances in {@code RetentionBatchProcessor}). In
     * contrast to {@link #deleteArrivalsByProcessInstanceId} the marker rows are NOT
     * spared: their owners (MI/ad-hoc handlers) are gone with the instance, nothing can
     * ever read or decrement them again.
     *
     * <p>{@code flushAutomatically} — страховка от расхождения кэша и БД: записи
     * arrival/marker-строк идут через {@code save()} и могут лежать в
     * persistence-контексте ещё не сброшенными, а провайдер по JPA-спеке не обязан
     * флашить перед bulk-запросом. На текущем стеке (Hibernate ORM 7.2) ordering
     * уже держится и БЕЗ флага — провайдер сам сбрасывает сессию перед HQL-mutation
     * (зафиксировано SQL-логом живого прогона: pending INSERT выполняется ДО
     * терминального DELETE), поэтому флаг здесь defense-in-depth, а не единственный
     * механизм; явный флаг держит инвариант независимо от смены провайдерской
     * дефолтной политики и совпадает с конвенцией проекта для @Modifying bulk-DML
     * (ActivityRepository, ProcessInstanceRepository).
     * {@code clearAutomatically} НЕ ставим: после терминального перехода в этой же
     * транзакции ещё живут managed-сущности (activity/token), и clear() потерял бы
     * незафлашенные изменения (сразу за нами {@code setCompletedAt} сам делает
     * flush+clear).
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM ParallelGatewayEntity pg WHERE pg.processInstanceId = :processInstanceId")
    void deleteBookkeepingByProcessInstanceId(UUID processInstanceId);
}
