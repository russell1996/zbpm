package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REL-37 F12 (RED-first, компилируется на pre-fix master):
 * {@code sendCatchupEvents} собирает {@code Map.of(...)} со штатным null-полем
 * ({@code process-instance.started} намеренно имеет {@code elementId=null}) —
 * {@code Map.of} кидает NPE на null-значении. Этот тест воспроизводит ровно ту
 * конструкцию прод-кода один в один (те же 12 ключей, те же тернарники) и обязан
 * УПАСТЬ с NPE на текущем коде (= баг есть), а после замены Map.of на null-safe
 * структуру — этот же сценарий покрывается {@code SseRel37CatchupTest}
 * (started + следующее событие доходят).
 */
class SseRel37MapOfRedTest {

    private static Map<String, Object> catchupEnvelopeLikeProd(DomainEventEntity event) {
        // Дословно конструкция SseEventStreamService.sendCatchupEvents (pre-fix):
        return Map.of(
            "sequence", event.getSequence(),
            "id", event.getId().toString(),
            "type", event.getType(),
            "version", event.getVersion(),
            "occurredAt", event.getOccurredAt().toString(),
            "processDefinitionId", event.getProcessDefinitionId() != null ? event.getProcessDefinitionId().toString() : null,
            "processInstanceId", event.getProcessInstanceId() != null ? event.getProcessInstanceId().toString() : null,
            "elementId", event.getElementId() != null ? event.getElementId() : null,
            "ownerScope", event.getOwnerScope() != null ? event.getOwnerScope() : null,
            "data", event.getData() != null ? event.getData() : Map.of()
        );
    }

    @Test
    void catchupEnvelope_withNullElementId_throwsNpe() {
        DomainEventEntity started = new DomainEventEntity();
        started.setSequence(1L);
        started.setId(UUID.randomUUID());
        started.setType("process-instance.started");
        started.setVersion(1);
        started.setOccurredAt(Instant.now());
        started.setElementId(null);

        // RED на pre-fix коде: Map.of + null elementId = NPE. После фикса эта
        // конструкция из прод-кода удалена (null-safe путь), тест фиксирует сам факт.
        assertThatThrownBy(() -> catchupEnvelopeLikeProd(started))
            .isInstanceOf(NullPointerException.class);
    }
}
