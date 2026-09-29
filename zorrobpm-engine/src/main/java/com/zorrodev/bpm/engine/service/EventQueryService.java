package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WO-DEBT-7 S9: JPA-backed reads for the {@code EventResource} domain events
 * endpoint. Moved verbatim out of the REST-layer resource (which stays behind
 * as a thin facade: auth + the {@code EventAuthzResolver} grant intersection,
 * then delegation). Every JPA read lives here; there are zero mutations in this
 * slice (pure {@code findAll(Specification)}/{@code findBy} windowed reads), so
 * there is no transaction boundary to move — both sides declare no
 * {@code @Transactional} (callers are themselves non-transactional read
 * endpoints; moving a boundary that does not exist would be the P-67-style
 * invention warned against in the dispatch — same as Slice 8).
 *
 * <p>Responsibility: the events-feed aggregate (windowed, cursor-based domain
 * events filtered by process-definition and arbitrary predicates). Deliberately
 * NOT inside {@code ProcessAuthzService} — that one answers "which definitions
 * does this principal see" (AuthZ), this one answers "which events should this
 * request return" (the actual timeline). Merging AuthZ visibility with the data
 * fetch would be god-class drift — the dispatch explicitly says the AuthZ path
 * ({@code ProcessAuthzService}) is NOT to be touched here.
 */
@Component
@RequiredArgsConstructor
public class EventQueryService {

    private final DomainEventRepository domainEventRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;

    /**
     * Ids of all versions of the given definition key; null when no key requested.
     * Used by the facade to intersect grant narrowing with the requested key
     * before calling the data fetch.
     */
    public List<UUID> resolveKeyPdIds(String processDefinitionKey) {
        if (processDefinitionKey == null || processDefinitionKey.isBlank()) {
            return null;
        }
        return processDefinitionRepository.findAll(
                (root, query, cb) -> cb.equal(root.get("key"), processDefinitionKey))
            .stream().map(ProcessDefinitionEntity::getId).toList();
    }

    /**
     * Windowed, cursor-based events query. All filters (grant-narrowing as
     * {@code pdFilter}, processInstanceId, type) are composed into the SQL
     * query BEFORE the cursor window is cut (WO-INT-7).
     *
     * <p>WO-REL-38: курсор — commit-ordered {@code feed_position}, НЕ raw
     * {@code sequence} (тот назначался при INSERT и мог навсегда пропустить
     * событие задержанной транзакции). Строки без позиции (джоб ещё не
     * назначил) в окно не попадают — это цена корректности: событие видимо
     * consumer'ам только после тика {@code FeedPositionAssigner}.
     *
     * @param since      exclusive cursor (feed position, NOT sequence)
     * @param pdFilter   grant-narrowed pdIds intersected with the key filter (null = unrestricted)
     * @param piId       optional process instance id
     * @param type       optional event type
     * @param maxResults window size (limit is maxResults + 1 internally for hasMore)
     * @return at most maxResults + 1 event envelopes ordered by feed position ASC
     */
    public List<Map<String, Object>> findEventEnvelopes(long since, Collection<UUID> pdFilter,
            UUID piId, String type, int maxResults) {
        Specification<DomainEventEntity> spec = (root, query, cb) -> cb.greaterThan(root.get("feedPosition"), since);
        final Collection<UUID> pdFilterF = pdFilter;
        if (pdFilter != null) {
            spec = spec.and((root, query, cb) -> root.get("processDefinitionId").in(pdFilterF));
        }
        if (piId != null) {
            final UUID piIdF = piId;
            spec = spec.and((root, query, cb) -> cb.equal(root.get("processInstanceId"), piIdF));
        }
        if (type != null && !type.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }

        List<DomainEventEntity> events = domainEventRepository.findBy(spec,
            q -> q.sortBy(Sort.by(Sort.Direction.ASC, "feedPosition"))
                .limit(maxResults + 1)
                .all());
        return events.stream().map(this::toEnvelope).toList();
    }

    /**
     * WO-REL-38: feed-позиция одной строки для live-пути SSE-моста. Строка
     * гарантированно закоммичена (мост читает её из закоммиченного брокерного
     * сообщения), но позиция может быть ещё не назначена — тогда empty, и
     * мост ждёт тик джоба ограниченное время. Empty также для неизвестного
     * sequence (чужой/синтетический id) — мост отбрасывает сразу, без ожидания.
     */
    public java.util.Optional<Long> resolveFeedPositionBySequence(long sequence) {
        return domainEventRepository.findById(sequence)
            .map(DomainEventEntity::getFeedPosition);
    }

    /**
     * WO-REL-38: существует ли строка с таким sequence вообще. Пара к
     * {@link #resolveFeedPositionBySequence}: неизвестный sequence мост
     * отбрасывает сразу, а существующий-но-без-позиции — ждёт тик джоба.
     */
    public boolean eventSequenceExists(long sequence) {
        return domainEventRepository.existsById(sequence);
    }

    private Map<String, Object> toEnvelope(DomainEventEntity event) {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("sequence", event.getSequence());
        // WO-REL-38: позиция курсора. Поле sequence оставлено (identity/отладка
        // и wire-совместимость), но курсор since/SSE id/дедуп читают ЭТО поле.
        envelope.put("feedPosition", event.getFeedPosition());
        envelope.put("id", event.getId().toString());
        envelope.put("type", event.getType());
        envelope.put("version", event.getVersion());
        envelope.put("occurredAt", event.getOccurredAt().toString());
        if (event.getProcessDefinitionId() != null) envelope.put("processDefinitionId", event.getProcessDefinitionId().toString());
        if (event.getProcessInstanceId() != null) envelope.put("processInstanceId", event.getProcessInstanceId().toString());
        if (event.getElementId() != null) envelope.put("elementId", event.getElementId());
        if (event.getOwnerScope() != null) envelope.put("ownerScope", event.getOwnerScope());
        envelope.put("data", event.getData() != null ? event.getData() : Map.of());
        return envelope;
    }
}
