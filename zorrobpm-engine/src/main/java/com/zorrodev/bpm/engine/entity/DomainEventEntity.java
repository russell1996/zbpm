package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only domain event table (ADR-7, WO-EVT-1).
 * Monotonic sequence for cursor-based pagination.
 */
@Entity
@Table(name = "events")
public class DomainEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sequence;

    /**
     * WO-REL-38 (F15): commit-ordered позиция в ленте. IDENTITY-sequence
     * назначается при INSERT, а не в порядке коммитов — курсор по нему мог
     * навсегда пропустить событие задержанной транзакции. Эту позицию ставит
     * {@code FeedPositionAssigner} только строкам завершённых транзакций;
     * consumer-курсор ({@code since}/Last-Event-ID) читает её, а не sequence.
     * NULL = позиция ещё не назначена (строка невидима курсору). Единственное
     * мутабельное поле сущности — таблица в остальном append-only.
     */
    @Column(name = "feed_position")
    private Long feedPosition;

    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 64)
    private String type;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "process_definition_id", updatable = false)
    private UUID processDefinitionId;

    @Column(name = "process_instance_id", updatable = false)
    private UUID processInstanceId;

    @Column(name = "element_id", updatable = false, length = 255)
    private String elementId;

    @Column(name = "owner_scope", updatable = false, length = 255)
    private String ownerScope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, Object> data;

    public Long getSequence() { return sequence; }
    public void setSequence(Long sequence) { this.sequence = sequence; }

    public Long getFeedPosition() { return feedPosition; }
    public void setFeedPosition(Long feedPosition) { this.feedPosition = feedPosition; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public UUID getProcessDefinitionId() { return processDefinitionId; }
    public void setProcessDefinitionId(UUID processDefinitionId) { this.processDefinitionId = processDefinitionId; }

    public UUID getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(UUID processInstanceId) { this.processInstanceId = processInstanceId; }

    public String getElementId() { return elementId; }
    public void setElementId(String elementId) { this.elementId = elementId; }

    public String getOwnerScope() { return ownerScope; }
    public void setOwnerScope(String ownerScope) { this.ownerScope = ownerScope; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
}
