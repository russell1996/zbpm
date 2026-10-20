package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-12 (F-20): non-interrupting event sub-process не оставляет
 * «лишний» root-токен в инстансе.
 *
 * <p>Аудит F-20 ({@code docs/audit/ZorroBPM-full-audit-2026-10-19.md}):
 * {@code EventTrigger.triggerEventSubprocess} для non-interrupting ESP создаёт
 * {@code branchToken = createToken(null)} — ROOT-токен. Он живёт рядом с
 * настоящим корневым токеном инстанса и выглядит как токен, управляющий
 * жизненным циклом инстанса (именно на {@code parentId == null} опирается
 * guard {@code isChildLinearBranch}, который отличает «ветку-сайд» от
 * управляющего токена). Комментарий WO-ENG-6 прямо предполагал, что такой
 * токен — child корневого.
 *
 * <p>Инвариант (G-P): у инстанса ровно ОДИН root-токен; ветка сайд-процесса
 * не должна выглядеть как второй управляющий токен.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Audit12EspRootTokenIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private ActivityService activityService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private JdbcTemplate jdbc;

    /** Root-токены инстанса: токены без родителя, несущие его activity-строки. */
    private long rootTokenCount(UUID processInstanceId) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT t.id) FROM tokens t JOIN activities a ON a.token = t.id "
                + "WHERE a.process_instance_id = ? AND t.parent_id IS NULL "
                + "AND t.scope_activity_id IS NULL",
            Long.class, processInstanceId);
        return count == null ? 0L : count;
    }

    @Transactional
    @Test
    void nonInterruptingEventSubProcess_leavesNoExtraRootToken() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-subprocess-noninterrupting.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of());
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        assertThat(rootTokenCount(pi)).as("one root token before the event sub-process").isEqualTo(1L);

        // non-interrupting ESP: срабатывает в своём scope, основной поток жив.
        activityService.correlateMessage("ping", pi, List.of());

        List<ActivityEntity> espEnds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> "evEnd".equals(a.getBpmnElementId()))
            .toList();
        assertThat(espEnds).as("the event sub-process ran and completed").hasSize(1);
        assertThat(espEnds.get(0).getStatus()).isEqualTo(ActivityStatus.COMPLETED);

        assertThat(rootTokenCount(pi))
            .as("the non-interrupting event sub-process must not leave a second root token")
            .isEqualTo(1L);
    }
}
