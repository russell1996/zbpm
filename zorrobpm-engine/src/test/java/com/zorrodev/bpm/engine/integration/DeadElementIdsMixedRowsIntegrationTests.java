package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.dto.IdDTO;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-35 раунд 8 (БЛОКИРУЮЩАЯ №1 red-team раунда 5): {@code ActivityRepository.findDeadElementIds}
 * возвращал elementId при ЛЮБОЙ нетерминальной строке, а контракт
 * ({@code DBServiceImpl.getExhaustedBoundaryElementIds}, «мёртв = мертвы ВСЕ копии») требует
 * «мёртв ТОЛЬКО если мертвы ВСЕ копии». Смешанные строки COMPLETED + живая одного elementId —
 * штатное состояние MI-копий (WO-ENG-23): старый запрос объявлял такой хост мёртвым, и его
 * граница ложно исключалась из «ещё может доставить» → inclusive-join срабатывал раньше времени
 * (и вторым разом по границе).
 *
 * <p>Тесты идут через НАСТОЯЩИЙ прод-путь ({@code DBService.getExhaustedBoundaryElementIds} →
 * {@code ActivityDbOperations} → {@code ActivityRepository.findDeadElementIds} на реальной БД),
 * а не через моки (G-N): откат JPQL-фикса обязан их ронять.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class DeadElementIdsMixedRowsIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private DBService dbService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private TokenRepository tokenRepository;

    private UUID startAnchorInstance() throws Exception {
        // Якорь ради FK activities.process_instance_id: живой инстанс без влияния на строки
        // под test-элементами (у них свои elementId, которых в фикстуре нет).
        String bpmn = Files.readString(Paths.get("src/test/files/test-c835-passthrough-fanout.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO started = runtimeService.startProcessInstance(dto);
        return started.getId();
    }

    private void row(UUID pi, String elementId, ActivityStatus status) {
        UUID tokenId = UUID.randomUUID();
        TokenEntity token = new TokenEntity();
        token.setId(tokenId);
        tokenRepository.save(token);
        ActivityEntity e = new ActivityEntity();
        e.setId(UUID.randomUUID());
        e.setProcessInstanceId(pi);
        e.setToken(tokenId);
        e.setBpmnElementId(elementId);
        e.setCreatedAt(Instant.now());
        e.setType(BpmnElementType.USER_TASK);
        e.setStatus(status);
        activityRepository.save(e);
    }

    @Transactional
    @Test
    void mixedCompletedAndLiveRowsOfOneElement_theElementIsNotDead() throws Exception {
        // Репро БЛОКИРУЮЩЕЙ №1 на уровне запроса: MI-хост, 1 копия завершена, 1 жива.
        // Старый JPQL возвращал taskMi (есть нетерминальная... нет — ЕСТЬ ТЕРМИНАЛЬНАЯ строка),
        // новый (NOT EXISTS живая строка того же elementId) — нет.
        UUID pi = startAnchorInstance();
        row(pi, "taskMi", ActivityStatus.COMPLETED);
        row(pi, "taskMi", ActivityStatus.IN_PROGRESS);

        assertThat(dbService.getExhaustedBoundaryElementIds(pi, Map.of("condMi", "taskMi")))
            .as("одна живая копия MI-хоста держит границу в возможных доставщиках")
            .isEmpty();
    }

    @Transactional
    @Test
    void allRowsTerminal_theElementIsDead() throws Exception {
        // Анти-перекоррекция: NOT EXISTS не должен превратиться в «никогда не мёртв» —
        // хост, у которого мертвы ВСЕ копии, остаётся мёртвым (проходит и на старом JPQL).
        UUID pi = startAnchorInstance();
        row(pi, "taskDead", ActivityStatus.COMPLETED);
        row(pi, "taskDead", ActivityStatus.CANCELLED);

        assertThat(dbService.getExhaustedBoundaryElementIds(pi, Map.of("condDead", "taskDead")))
            .as("все копии мертвы — граница снята")
            .containsExactly("condDead");
    }

    @Transactional
    @Test
    void elementWithNoRows_theElementIsNotDead() throws Exception {
        // BLOCKER-6 на уровне запроса: хост, который ещё не начинался, строк не имеет —
        // он достижим по графу, а не мёртв (проходит и на старом JPQL).
        UUID pi = startAnchorInstance();

        assertThat(dbService.getExhaustedBoundaryElementIds(pi, Map.of("tmrNew", "taskNew")))
            .as("хост без строк не мёртв (он ещё не начал) — граница остаётся доставщиком")
            .isEmpty();
    }
}
