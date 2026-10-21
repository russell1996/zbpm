package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.MessagePublishResultDTO;
import com.zorrodev.bpm.contract.dto.PublishMessageDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-39: изоляция корреляции — публикация сообщения БЕЗ ключа корреляции
 * не будит подписку, у которой ключ задан.
 *
 * <p><b>Что это чинит.</b> {@code ActivityServiceImpl.correlateCounted} при
 * {@code correlationKey == null} искал подписки по ИМЕНИ сообщения, игнорируя ключ
 * каждой подписки. Значит, бесключевая публикация продвигала ЧУЖИЕ инстансы:
 * изоляция корреляции между инстансами/клиентами была нарушена.
 * Живой Zeebe 8.5.0 (замер WO-OPS-28 Фаза 1, §7 Ф1-1): публикация без ключа не
 * коррелирует <b>ни с чем</b> — {@code publish {key: null, correlated: 0}}, исход
 * {@code stuck}. У нас до фикса: {@code completed}, {@code correlated: 1}.</p>
 *
 * <p><b>Что НЕ меняется этим WO.</b> Режим «без ключа» у нас остаётся (в Zeebe 8.5
 * подписка без ключа невозможна — там ключ обязателен, Ф1-4; у нас ключ опционален),
 * поэтому бесключевая публикация будит бесключевые подписки, но НЕ ключевые.
 * Публичный REST-контракт не менялся (решение — в отчёте WO-OPS-39, §«Судьба
 * публикации без ключа»).</p>
 *
 * <p>G-N: каждое наблюдение снято с ПРОД-пути — публикация идёт через
 * {@code RuntimeService.publishMessage} (тот же метод, что и REST-эндпоинт
 * {@code POST /messages/publish}), бросок — через реальный движок (sendTask с
 * messageRef → {@code MessageThrowHandler}).</p>
 *
 * <p>G-P: инвариант утечек проверяется явно (свой baseline + свой чекер) для
 * инстансов каждого кейса, плюс глобальный enforce-слушатель
 * {@code LeakInvariantTestListener} (режим enforce по умолчанию).</p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
public class KeylessPublishCorrelationIsolationIntegrationTests {

    /** Подписка С ключом корреляции (=orderId), ожидание сообщения "orderUpdate". */
    private static final String KEYED_CATCH_FIXTURE = "src/test/files/test-message-correlation-key.bpmn";
    /** Подписка БЕЗ ключа корреляции, ожидание сообщения "order-approved". */
    private static final String KEYLESS_CATCH_FIXTURE = "src/test/files/test-message.bpmn";
    /** Старт по сообщению (message start event), сообщение "order-received". */
    private static final String MESSAGE_START_FIXTURE = "src/test/files/test-message-start.bpmn";
    /** Ожидатель с ключом: сообщение "sharedNote", ключ =k. */
    private static final String KEYED_WAITER_FIXTURE = "src/test/files/test-ops39-keyed-waiter.bpmn";
    /** Ожидатель без ключа: сообщение "sharedNote". */
    private static final String KEYLESS_WAITER_FIXTURE = "src/test/files/test-ops39-keyless-waiter.bpmn";
    /** Бросающий процесс: sendTask с messageRef — сообщение "sharedNote" (внутренний бросок). */
    private static final String THROWER_FIXTURE = "src/test/files/test-ops39-thrower.bpmn";

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ─────────────────────────────── публикация (REST-путь) ───────────────────────────────

    /**
     * Ключевой кейс WO-OPS-39: бесключевая публикация не будит ключевую подписку.
     * Оракул Zeebe 8.5.0: {@code publish {key: null, correlated: 0}} → инстанс {@code stuck}.
     */
    @Test
    void keylessPublish_doesNotWakeKeyedSubscription() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);
        ProcessDefinition model = deploy(KEYED_CATCH_FIXTURE);
        UUID pi = start(model.getId(), "orderId", "A");

        // инстанс припаркован на ключевой подписке (=orderId → "A")
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();

        MessagePublishResultDTO result = publish("orderUpdate", null, null);

        assertThat(result.getCorrelated())
            .as("бесключевая публикация не должна будить ключевую подписку (Zeebe 8.5: correlated=0)")
            .isZero();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("инстанс с ключевой подпиской остаётся ждать (Zeebe 8.5: stuck)")
            .isNull();
        assertNoLeaks(baseline, pi);
    }

    /**
     * Контроль «режима без ключа»: бесключевая публикация ДОЛЖНА будить бесключевую
     * подписку (у нас ключ корреляции опционален — Ф1-4). Фикс не должен сломать
     * собственный бесключевой режим.
     */
    @Test
    void keylessPublish_wakesKeylessSubscription() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);
        ProcessDefinition model = deploy(KEYLESS_CATCH_FIXTURE);
        UUID pi = start(model.getId());

        MessagePublishResultDTO result = publish("order-approved", null, null);

        assertThat(result.getCorrelated()).as("бесключевая подписка просыпается").isEqualTo(1);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(baseline, pi);
    }

    /** Ключевой путь не сломан: публикация с СОВПАДАЮЩИМ ключом будит подписку. */
    @Test
    void keyedPublish_wakesSubscriptionWithMatchingKey() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);
        ProcessDefinition model = deploy(KEYED_CATCH_FIXTURE);
        UUID pi = start(model.getId(), "orderId", "A");

        MessagePublishResultDTO result = publish("orderUpdate", "A", null);

        assertThat(result.getCorrelated()).as("публикация с совпадающим ключом будит подписку").isEqualTo(1);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertNoLeaks(baseline, pi);
    }

    /** Публикация с ЧУЖИМ ключом не будит никого (поведение не менялось). */
    @Test
    void keyedPublish_withForeignKey_wakesNobody() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);
        ProcessDefinition model = deploy(KEYED_CATCH_FIXTURE);
        UUID pi = start(model.getId(), "orderId", "A");

        MessagePublishResultDTO result = publish("orderUpdate", "Z", null);

        assertThat(result.getCorrelated()).isZero();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
        assertNoLeaks(baseline, pi);
    }

    /** Message start не сломан: бесключевая публикация поднимает новый инстанс. */
    @Test
    void keylessPublish_stillStartsMessageStartInstance() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);
        deploy(MESSAGE_START_FIXTURE);

        MessagePublishResultDTO result = publish("order-received", null, null);

        assertThat(result.getStarted()).as("message start event: бесключевая публикация поднимает инстанс").isEqualTo(1);
        assertThat(result.getCorrelated()).isZero();
        assertNoLeaks(baseline);
    }

    // ─────────────────────────────── внутренний бросок (throw-handler) ───────────────────────────────

    /**
     * Путь «throw-handler»: бросок сообщения процессом (sendTask с messageRef) —
     * это бесключевая публикация, значит она НЕ должна будить ключевую подписку.
     */
    @Test
    void internalMessageThrow_doesNotWakeKeyedSubscription() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);
        ProcessDefinition waiter = deploy(KEYED_WAITER_FIXTURE);
        UUID waiterPi = start(waiter.getId(), "k", "KEY-1");
        assertThat(queryService.getProcessInstance(waiterPi).getCompletedAt()).isNull();

        ProcessDefinition thrower = deploy(THROWER_FIXTURE);
        UUID throwerPi = start(thrower.getId());

        assertThat(queryService.getProcessInstance(throwerPi).getCompletedAt())
            .as("бросающий процесс отработал (бросок состоялся)")
            .isNotNull();
        assertThat(queryService.getProcessInstance(waiterPi).getCompletedAt())
            .as("внутренний бесключевой бросок не будит ключевую подписку")
            .isNull();
        assertNoLeaks(baseline, waiterPi, throwerPi);
    }

    /** Контроль пути «throw-handler»: бесключевой бросок будит бесключевую подписку. */
    @Test
    void internalMessageThrow_wakesKeylessSubscription() throws Exception {
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);
        ProcessDefinition waiter = deploy(KEYLESS_WAITER_FIXTURE);
        UUID waiterPi = start(waiter.getId());
        assertThat(queryService.getProcessInstance(waiterPi).getCompletedAt()).isNull();

        ProcessDefinition thrower = deploy(THROWER_FIXTURE);
        UUID throwerPi = start(thrower.getId());

        assertThat(queryService.getProcessInstance(waiterPi).getCompletedAt())
            .as("внутренний бесключевой бросок будит бесключевую подписку")
            .isNotNull();
        assertThat(queryService.getProcessInstance(throwerPi).getCompletedAt()).isNotNull();
        assertNoLeaks(baseline, waiterPi, throwerPi);
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private ProcessDefinition deploy(String fixture) throws Exception {
        return processDefinitionService.addProcessDefinition(Files.readString(Paths.get(fixture)));
    }

    /** Старт инстанса; {@code nameValuePairs} — пары «имя переменной, значение». */
    private UUID start(UUID definitionId, String... nameValuePairs) {
        List<ProcessVariable> vars = new ArrayList<>();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            ProcessVariable pv = new ProcessVariable();
            pv.setName(nameValuePairs[i]);
            pv.setType(ProcessVariableType.STRING);
            pv.setValue(nameValuePairs[i + 1]);
            vars.add(pv);
        }
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        dto.setVariables(vars);
        return runtimeService.startProcessInstance(dto).getId();
    }

    /** Публикация сообщения — тот же боевой путь, что у REST-эндпоинта. */
    private MessagePublishResultDTO publish(String messageName, String correlationKey, UUID processInstanceId) {
        PublishMessageDTO dto = new PublishMessageDTO();
        dto.setMessageName(messageName);
        dto.setCorrelationKey(correlationKey);
        dto.setProcessInstanceId(processInstanceId);
        dto.setVariables(List.of());
        return runtimeService.publishMessage(dto);
    }

    /**
     * G-P: инвариант утечек для инстансов ЭТОГО кейса (свой baseline → свои
     * нарушения). Чужие инстансы общей БД не приписываются тесту (WO-OPS-32).
     */
    private void assertNoLeaks(LeakInvariantChecker.Baseline baseline, UUID... instances) {
        Set<UUID> mine = Set.of(instances);
        List<LeakInvariantChecker.Violation> violations = LeakInvariantChecker.check(jdbcTemplate, baseline).own().stream()
            .filter(v -> instances.length == 0 || mine.contains(v.instanceId()))
            .toList();
        assertThat(violations)
            .as("LEAK-INVARIANT (G-P): у завершённых инстансов кейса не осталось живого мусора")
            .isEmpty();
    }
}
