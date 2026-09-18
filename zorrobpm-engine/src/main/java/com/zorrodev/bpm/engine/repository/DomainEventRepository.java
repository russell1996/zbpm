package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * WO-INT-7: extends JpaSpecificationExecutor so /events can compose its filters
 * (cursor, grant narrowing, processDefinitionKey, type, processInstanceId) into ONE
 * SQL query — filtering must happen before the window is cut, not after.
 */
public interface DomainEventRepository
        extends JpaRepository<DomainEventEntity, Long>, JpaSpecificationExecutor<DomainEventEntity> {

    /**
     * WO-REL-38: курсор — commit-ordered {@code feed_position}, НЕ raw
     * {@code sequence} (тот назначался при INSERT и мог навсегда пропустить
     * событие задержанной транзакции). Строки без позиции (ещё не обработанные
     * джобом) сюда не попадают. Параметр {@code since} — exclusive позиция.
     * Сейчас прямых прод-вызывающих нет (живой путь — {@code EventQueryService}
     * через Specification); метод оставлен согласованным с курсором ленты.
     */
    @Query(value = "SELECT * FROM events WHERE feed_position > :since ORDER BY feed_position ASC LIMIT :limit", nativeQuery = true)
    List<DomainEventEntity> findSince(@Param("since") long since, @Param("limit") int limit);

    /**
     * WO-AUDIT-3 (P4): the per-instance query is bounded — a long-lived instance must
     * not materialize its whole history. (Currently no prod caller; the live
     * instance-scoped path is {@code EventResource} via the windowed {@code findBy}
     * above. Kept bounded so no unbounded instance query exists in the codebase.)
     */
    @Query(value = "SELECT * FROM events WHERE process_instance_id = :processInstanceId ORDER BY sequence ASC LIMIT :limit", nativeQuery = true)
    List<DomainEventEntity> findByProcessInstanceId(@Param("processInstanceId") UUID processInstanceId, @Param("limit") int limit);

    @Query(value = "SELECT COALESCE(MAX(sequence), 0) FROM events", nativeQuery = true)
    long getMaxSequence();

    /**
     * Cursor-based query with AuthZ filtering: only events whose process_definition_id
     * is in the allowed set. Used by the SSE catch-up stream.
     * WO-REL-38: курсор и сортировка — по {@code feed_position} (см. {@link #findSince}).
     */
    @Query(value = "SELECT * FROM events " +
        "WHERE feed_position > :since " +
        "AND process_definition_id IN :pdIds " +
        "ORDER BY feed_position ASC LIMIT :limit", nativeQuery = true)
    List<DomainEventEntity> findSinceForPrincipal(
        @Param("since") long since,
        @Param("pdIds") Collection<UUID> processDefinitionIds,
        @Param("limit") int limit);
}
