package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-12 (F-02) ↔ WO-AUDIT-11 (F-09): «своя строка» и «чужая» в одном сценарии.
 *
 * <p>Сценарий: error END EVENT внутри подпроцесса; ошибку ловит граница на этом
 * подпроцессе. Граница гасит ВЕСЬ скоуп — и сестринскую задачу форка, и контейнер.
 *
 * <p>Инварианты, которые должны держаться ОДНОВРЕМЕННО:
 * <ul>
 *   <li><b>(а) причина — не жертва:</b> строка САМОГО бросившего элемента
 *       ({@code errEnd}) заканчивается COMPLETED. Её погасило гашение скоупа,
 *       вызванное САМИМ этим броском (WO-AUDIT-11, F-09). Завершает её УЗКИЙ переход
 *       {@code completeActivityAfterHandledError} — обычный {@code completeActivity}
 *       (F-02) такую строку не переворачивает.</li>
 *   <li><b>(б) жертвы остаются жертвами:</b> строки, погашенные гашением скоупа
 *       ({@code siblingTask}, контейнер {@code sub1}), остаются CANCELLED. Узкий переход
 *       трогает ТОЛЬКО строку бросившего элемента — чужие отменённые строки он не
 *       «воскрешает» (иначе рушился бы инвариант F-02: позднее завершение не
 *       переписывает CANCELLED-строку).</li>
 * </ul>
 *
 * <p>Инвариант утечек (G-P) проверяется здесь (явно) и глобальным чекером
 * {@code LeakInvariantExtension} (WO-OPS-26) — прогон с
 * {@code -Dzbpm.leakInvariant=enforce} обязан быть зелёным.
 *
 * <p>Каждый шаг — в СВОЕЙ транзакции (TransactionTemplate): тот же контур, что у
 * per-HTTP-запроса, поэтому закоммиченное состояние видно следующему шагу, а глобальный
 * чекер утечек действительно осматривает завершённый инстанс.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Audit12ErrorEndCauseAndVictimsIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private UUID start() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-audit12-error-end-cause-vs-victims.bpmn"));
        return tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });
    }

    private List<ActivityEntity> activities(UUID pi, String elementId) {
        return tx.execute(s -> activityRepository.findByProcessInstanceIdOrderByCreatedAtAsc(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .toList());
    }

    private List<IncidentEntity> openIncidents(UUID pi) {
        return tx.execute(s -> incidentRepository.findAll().stream()
            .filter(i -> i.getCompletedAt() == null)
            .filter(i -> activityRepository.findById(i.getActivityId())
                .map(a -> pi.equals(a.getProcessInstanceId()))
                .orElse(false))
            .toList());
    }

    private ProcessInstance instance(UUID pi) {
        return tx.execute(s -> queryService.getProcessInstance(pi));
    }

    /**
     * (а) бросивший элемент — COMPLETED; (б) жертвы гашения — CANCELLED.
     */
    @Test
    void errorEndCaughtByBoundary_throwingElementCompletesAndScopeVictimsStayCancelled() throws Exception {
        UUID pi = start();

        assertThat(openIncidents(pi))
            .as("граница объемлющего подпроцесса обязана поймать ошибку — инцидентов быть не должно")
            .isEmpty();

        // (а) ПРИЧИНА: строка бросившего элемента — COMPLETED (WO-AUDIT-11, F-09).
        List<ActivityEntity> errEnd = activities(pi, "errEnd");
        assertThat(errEnd).hasSize(1);
        assertThat(errEnd.get(0).getStatus())
            .as("бросивший error-end элемент отработал (ошибку поймали границей) — его строка COMPLETED")
            .isEqualTo(ActivityStatus.COMPLETED);

        // (б) ЖЕРТВЫ: сестринская задача форка погашена гашением скоупа и остаётся
        // CANCELLED — узкий переход не переворачивает чужие отменённые строки.
        List<ActivityEntity> sibling = activities(pi, "siblingTask");
        assertThat(sibling).hasSize(1);
        assertThat(sibling.get(0).getStatus())
            .as("сестринская задача — жертва гашения скоупа, она остаётся CANCELLED")
            .isEqualTo(ActivityStatus.CANCELLED);

        // контейнер погашенного скоупа — тоже CANCELLED
        List<ActivityEntity> sub = activities(pi, "sub1");
        assertThat(sub).hasSize(1);
        assertThat(sub.get(0).getStatus()).isEqualTo(ActivityStatus.CANCELLED);

        // путь границы взят
        List<ActivityEntity> afterBoundary = activities(pi, "afterBoundary");
        assertThat(afterBoundary).hasSize(1);
        assertThat(afterBoundary.get(0).getStatus()).isEqualTo(ActivityStatus.CREATED);
    }

    /**
     * G-P: после завершения пути границы инстанс доходит до конца, и в нём не остаётся
     * мусора — живых activity, открытых задач, таймеров, подписок, инцидентов и токенов
     * с несведённым счётчиком ветвей.
     */
    @Test
    void afterBoundaryCompleted_theInstanceEndsWithoutLiveResidue() throws Exception {
        UUID pi = start();
        UUID afterBoundaryId = activities(pi, "afterBoundary").get(0).getId();

        tx.executeWithoutResult(s -> runtimeService.completeUserTask(afterBoundaryId, List.of()));

        assertThat(instance(pi).getCompletedAt())
            .as("после границы инстанс доходит до конца")
            .isNotNull();

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM activities WHERE process_instance_id = ?"
                + " AND status IN ('CREATED','IN_PROGRESS')", Long.class, pi))
            .as("в завершённом инстансе не осталось живых activity").isZero();
        // Открытая task-строка — мусор, только если рядом живая activity-строка того же
        // инстанса (orphan-пара activity+task). Сама по себе открытая task-строка на
        // ПОГАШЕННОЙ activity — не мусор: ровно это правило у глобального чекера
        // утечек (LeakInvariantChecker, блок (ж)).
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_tasks t WHERE t.process_instance_id = ?"
                + " AND t.completed_at IS NULL AND EXISTS ("
                + "  SELECT 1 FROM activities a WHERE a.process_instance_id = t.process_instance_id"
                + "   AND a.status IN ('CREATED','IN_PROGRESS'))",
            Long.class, pi))
            .as("открытых задач на живых activity не осталось").isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM timer_jobs WHERE process_instance_id = ?", Long.class, pi))
            .as("таймеров не осталось").isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM message_subscriptions WHERE process_instance_id = ?", Long.class, pi))
            .as("подписок не осталось").isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM incidents i JOIN activities a ON a.id = i.activity_id"
                + " WHERE a.process_instance_id = ? AND i.completed_at IS NULL", Long.class, pi))
            .as("открытых инцидентов не осталось").isZero();
    }
}
