package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-35 раунд 8 (БЛОКИРУЮЩАЯ №1 red-team раунда 5) на живом PostgreSQL: тот же контракт, что
 * {@code DeadElementIdsMixedRowsIntegrationTests} на H2, но запросом, который реально выполняет
 * PostgreSQL (JPQL → SQL через Hibernate; H2 ≠ PG — V11/P-17).
 *
 * <p>Идёт через НАСТОЯЩИЙ прод-путь ({@code DBService.getExhaustedBoundaryElementIds} →
 * {@code ActivityRepository.findDeadElementIds}), откат JPQL-фикса обязан ронять первый тест.
 */
public class DeadElementIdsMixedRowsPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired DBService dbService;
    @Autowired ActivityRepository activityRepository;
    @Autowired TokenRepository tokenRepository;
    @Autowired JdbcTemplate jdbc;

    private UUID startAnchorInstance() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c835-passthrough-fanout.bpmn"));
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionService.addProcessDefinition(bpmn).getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private void row(UUID pi, String elementId, ActivityStatus status) {
        UUID tokenId = UUID.randomUUID();
        TokenEntity token = new TokenEntity();
        token.setId(tokenId);
        tokenRepository.saveAndFlush(token);
        ActivityEntity e = new ActivityEntity();
        e.setId(UUID.randomUUID());
        e.setProcessInstanceId(pi);
        e.setToken(tokenId);
        e.setBpmnElementId(elementId);
        e.setCreatedAt(Instant.now());
        e.setType(BpmnElementType.USER_TASK);
        e.setStatus(status);
        activityRepository.saveAndFlush(e);
    }

    @Test
    void mixedCompletedAndLiveRowsOfOneElement_theElementIsNotDead() throws Exception {
        // MI-хост: 1 копия завершена, 1 жива. Старый JPQL («есть нетерминальная строка») возвращал
        // taskMi и снимал его границу с доставщиков — join срабатывал раньше времени.
        UUID pi = startAnchorInstance();
        row(pi, "taskMi", ActivityStatus.COMPLETED);
        row(pi, "taskMi", ActivityStatus.IN_PROGRESS);

        assertThat(dbService.getExhaustedBoundaryElementIds(pi, Map.of("condMi", "taskMi")))
            .as("одна живая копия MI-хоста держит границу в возможных доставщиках (PostgreSQL)")
            .isEmpty();
    }

    @Test
    void allRowsTerminal_theElementIsDead() throws Exception {
        // Анти-перекоррекция: мертвы ВСЕ копии — хост мёртв (проходит и на старом JPQL).
        UUID pi = startAnchorInstance();
        row(pi, "taskDead", ActivityStatus.COMPLETED);
        row(pi, "taskDead", ActivityStatus.CANCELLED);

        assertThat(dbService.getExhaustedBoundaryElementIds(pi, Map.of("condDead", "taskDead")))
            .as("все копии мертвы — граница снята (PostgreSQL)")
            .containsExactly("condDead");
    }
}
