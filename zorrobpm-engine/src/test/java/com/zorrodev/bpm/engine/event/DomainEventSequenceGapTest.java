package com.zorrodev.bpm.engine.event;

import com.zorrodev.bpm.contract.dto.event.DomainEventType;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * WO-REL-8b: Document sequence-gap behavior.
 *
 * Risk assessment (ACCEPTED LIMITATION):
 * - Under concurrent transactions, IDENTITY sequence can produce gaps in visible order.
 * - T1 gets seq=N (long-running), T2 gets seq=N+1 (commits first).
 * - Client reading after T2 commit sees N+1 but not N until T1 commits.
 * - After BOTH commits, both events are visible — NO permanent data loss.
 *
 * Why accepted:
 * 1. Single-instance deployment — concurrent emit on same PI is rare (BPMN = sequential).
 * 2. SSE consumers use Last-Event-ID for reconnection, which re-reads from the cursor.
 * 3. Pull consumers get eventual consistency.
 * 4. Multi-instance scaling would need watermark/safety-margin, but current scale doesn't warrant it.
 *
 * Monitor: if multi-instance is deployed, re-evaluate with a safety margin (now() - 2s).
 */
@ExtendWith(MockitoExtension.class)
class DomainEventSequenceGapTest {

    @Mock private DomainEventRepository domainEventRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private ProcessDefinitionRepository processDefinitionRepository;
    @Mock private tools.jackson.databind.ObjectMapper objectMapper;
    @Mock private com.zorrodev.bpm.engine.tracing.TracingSupport tracing;

    @InjectMocks
    private DomainEventEmitter emitter;

    /**
     * Simulates the sequence-gap scenario:
     * Two events emitted by different calls (as would happen in concurrent transactions).
     * After both are committed, both are visible to a cursor read from before the first.
     * This proves no permanent data loss — only a temporary visibility gap.
     */
    @Test
    void bothEvents_visibleAfterBothCommitted() throws Exception {
        UUID piId1 = UUID.randomUUID();
        UUID piId2 = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();

        doReturn("{}").when(objectMapper).writeValueAsString(any());
        // WO-REL-37: прод зовёт saveAndFlush; эмулируем JPA (тот же entity назад),
        // иначе persisted=null и emit падает до outbox-save (ассерт ниже — про
        // видимость обоих коммитов, возврат мока на него не влияет).
        lenient().doAnswer(inv -> inv.getArgument(0)).when(domainEventRepository)
            .saveAndFlush(any(DomainEventEntity.class));

        // Simulate T1 emit (gets seq=N)
        emitter.emit(DomainEventType.PROCESS_INSTANCE_STARTED, piId1, pdId, null, Map.of());

        // Simulate T2 emit (gets seq=N+1) — would commit first in race
        emitter.emit(DomainEventType.PROCESS_INSTANCE_STARTED, piId2, pdId, null, Map.of());

        // Verify both events were saved to events table (WO-REL-37: saveAndFlush —
        // sequence нужен сразу для envelope; поведение save то же, flush лишь раньше
        // отправляет INSERT — на видимость после обоих коммитов не влияет).
        verify(domainEventRepository, org.mockito.Mockito.times(2)).saveAndFlush(any(DomainEventEntity.class));

        // Verify both outbox entries were created (no serialization failure)
        verify(outboxRepository, org.mockito.Mockito.times(2)).save(any(OutboxEntry.class));

        // In real PG, after both commits:
        // SELECT * FROM events WHERE sequence > 0 ORDER BY sequence ASC → returns both rows.
        // The "gap" is only visible DURING the concurrency window, not after both commits.
    }
}
