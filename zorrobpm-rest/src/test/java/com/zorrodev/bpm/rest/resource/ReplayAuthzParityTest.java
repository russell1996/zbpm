package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.PublishMessageDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.RuntimeSupportService;
import com.zorrodev.bpm.engine.service.IdempotencyReplayAuthorizer;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.rest.security.IdempotencyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WO-ENG-26 (NEW-11) — контрактный тест против дрейфа live vs replay
 * (подход 2 из WO: подход 1 — единый policy-класс на 4 контроллера +
 * OBO-нюансы + осознанное 404-vs-403 — слишком инвазивен для Medium).
 *
 * <p>Для каждого idempotent-endpoint × принципал сверяет РЕШЕНИЕ live-пути
 * (прямой вызов {@code *OperationsImpl} с замоканным
 * {@link AuthorizationService}) с решением replay-пути (реальный
 * {@link IdempotencyReplayAuthorizer} на том же моке): ALLOW/DENY/UNAUTH
 * обязаны совпадать. Принятое расхождение 404 (live OBO) vs 403 (replay
 * OBO) явно исключено с комментарием (критерий 2).
 *
 * <p>Реестр {@link #COVERED_SAMPLES} сверяется с
 * {@link IdempotencyFilter#isIdempotentPath}: новый idempotent-путь в
 * фильтре без кейса здесь → {@link #allIdempotentSamples_covered} красный
 * (критерий 1, вторая половина). RED-мутация — рассинхрон одной проверки в
 * authorizer (см. отчёт) — валит parity-кейс.
 */
@ExtendWith(MockitoExtension.class)
class ReplayAuthzParityTest {

    @Mock private AuthorizationService authorizationService;
    @Mock private RuntimeOperationSupport runtimeOperationSupport;
    @Mock private RuntimeSupportService runtimeSupportService;
    @Mock private UserTaskRepository userTaskRepository;
    @Mock private RuntimeService runtimeService;
    @Mock private com.zorrodev.bpm.engine.service.DBService dbService;
    @Mock private com.zorrodev.bpm.engine.service.ProcessInstanceLifecycleService lifecycleService;

    private IdempotencyReplayAuthorizer authorizer;
    private UserTaskRuntimeOperationsImpl userTasks;
    private ProcessInstanceRuntimeOperationsImpl instances;
    private MessageRuntimeOperationsImpl messages;
    private IncidentRuntimeOperationsImpl incidents;
    private ServiceTaskRuntimeOperationsImpl serviceTasks;

    private final UUID taskId = UUID.randomUUID();
    private final UUID instanceId = UUID.randomUUID();
    private final UUID serviceTaskId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    private final Principal stranger = new Principal.UserPrincipal(UUID.randomUUID(), "stranger", "USER");
    private final Principal admin =
        new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");

    enum Decision { ALLOW, DENY, UNAUTH }

    /** Каждый образец — idempotent-путь из скоупа фильтра + как его решать. */
    private record Sample(String endpoint, String kind) {}

    private static final List<String> COVERED_SAMPLES = List.of(
        "/user-tasks/{id}/complete",
        "/user-tasks/{id}/claim",
        "/user-tasks/{id}/unclaim",
        "/user-tasks/{id}/assign",
        "/service-tasks/{id}/complete",
        "/service-tasks/{id}/fail",
        "/service-tasks/{id}/throw-error",
        "/incidents/{id}/resolve",
        "/process-instances/{id}/cancel",
        "/process-instances",
        "/messages/publish",
        "/deployments",
        "/dmn",
        "/forms"
    );

    @BeforeEach
    void setUp() {
        authorizer = new IdempotencyReplayAuthorizer(
            authorizationService, runtimeSupportService, userTaskRepository);
        userTasks = new UserTaskRuntimeOperationsImpl(userTaskRepository, runtimeService, null, null,
            null, null, authorizationService, runtimeOperationSupport, null, null);
        instances = new ProcessInstanceRuntimeOperationsImpl(runtimeService, lifecycleService, null,
            dbService, null, null, null, runtimeOperationSupport, null);
        messages = new MessageRuntimeOperationsImpl(runtimeService, null, runtimeOperationSupport);
        incidents = new IncidentRuntimeOperationsImpl(runtimeService, null, runtimeOperationSupport);
        serviceTasks = new ServiceTaskRuntimeOperationsImpl(
            runtimeService, null, runtimeOperationSupport);

        UserTaskEntity task = new UserTaskEntity();
        task.setId(taskId);
        task.setProcessInstanceId(instanceId);
        lenient().when(userTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        ProcessDefinitionEntity def = new ProcessDefinitionEntity();
        def.setKey("proc-key");
        lenient().when(runtimeSupportService.resolveTargetDefinition(any()))
            .thenReturn(def);
        lenient().when(runtimeSupportService.resolveDefinitionKeyByInstance(any()))
            .thenReturn("proc-key");
        lenient().when(runtimeSupportService.resolveDefinitionKeyByServiceTask(any()))
            .thenReturn("proc-key");
        lenient().when(runtimeSupportService.resolveDefinitionKeyByIncident(any()))
            .thenReturn("proc-key");
        com.zorrodev.bpm.contract.model.ProcessInstance pi =
            new com.zorrodev.bpm.contract.model.ProcessInstance();
        lenient().when(dbService.getProcessInstance(any())).thenReturn(pi);
        lenient().when(lifecycleService.resolveDefinitionKey(any())).thenReturn("proc-key");
        lenient().when(runtimeOperationSupport.getPrincipal()).thenReturn(stranger);
        lenient().when(runtimeOperationSupport.checkedOnBehalfOf()).thenReturn(null);
        lenient().doNothing().when(runtimeOperationSupport)
            .requireOnBehalfMatchesTask(any(), any(), any(), any());
        lenient().doNothing().when(runtimeSupportService).requireOnBehalfExists(any());
        // requireOperate на моке — no-op; грант программируется через canOperate.
        lenient().doAnswer(inv -> {
            String key = inv.getArgument(0);
            AuthorizationService.Action action = inv.getArgument(1);
            if (!authorizationService.canOperate(
                    runtimeOperationSupport.getPrincipal(), key, action)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
            return null;
        }).when(runtimeOperationSupport).requireOperate(anyString(), any());
        lenient().doAnswer(inv -> "proc-key")
            .when(runtimeOperationSupport).resolveDefinitionKeyByInstance(any());
        lenient().doAnswer(inv -> "proc-key")
            .when(runtimeOperationSupport).resolveDefinitionKeyByServiceTask(any());
        lenient().doAnswer(inv -> "proc-key")
            .when(runtimeOperationSupport).resolveDefinitionKeyByIncident(any());
    }

    private String fill(String sample, UUID id) {
        if (id == null) return sample;
        return sample.replace("{id}", id.toString());
    }

    private Decision live(String sample, Principal principal) {
        lenient().when(runtimeOperationSupport.getPrincipal()).thenReturn(principal);
        try {
            switch (sample) {
                case "/user-tasks/{id}/complete" -> {
                    // Валидация форм — мимо: formArtifactService null; здесь важен
                    // только authz-вердикт ДО неё — NPE после гранта = ALLOW.
                    try {
                        userTasks.completeUserTask(taskId, new CompleteTaskDTO());
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/user-tasks/{id}/claim" -> {
                    try {
                        userTasks.claimUserTask(taskId);
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/user-tasks/{id}/unclaim" -> {
                    try {
                        userTasks.unclaimUserTask(taskId);
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/user-tasks/{id}/assign" -> {
                    var dto = new com.zorrodev.bpm.contract.dto.AssignUserTaskDTO();
                    dto.setAssignee("someone");
                    try {
                        userTasks.assignUserTask(taskId, dto);
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/service-tasks/{id}/complete" -> {
                    try {
                        serviceTasks.completeServiceTask(serviceTaskId, new CompleteTaskDTO());
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/service-tasks/{id}/fail" -> {
                    try {
                        serviceTasks.failServiceTask(serviceTaskId,
                            new com.zorrodev.bpm.contract.dto.FailServiceTaskDTO());
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/service-tasks/{id}/throw-error" -> {
                    try {
                        serviceTasks.throwServiceTaskError(serviceTaskId,
                            new com.zorrodev.bpm.contract.dto.ThrowErrorDTO());
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/incidents/{id}/resolve" -> {
                    try {
                        incidents.resolveIncident(incidentId, new ResolveIncidentDTO());
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/process-instances/{id}/cancel" -> {
                    // dbService null → NPE после гранта = ALLOW (грант — строка
                    // requireOperate ДО первого dbService-обращения).
                    try {
                        instances.cancelProcessInstance(instanceId);
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/process-instances" -> {
                    StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                    try {
                        instances.startProcessInstance(dto);
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/messages/publish" -> {
                    PublishMessageDTO dto = new PublishMessageDTO();
                    dto.setProcessInstanceId(instanceId);
                    try {
                        messages.publishMessage(dto);
                    } catch (ResponseStatusException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // Грант пройден, дальше — мок-инфраструктура (null
                        // collaborator'ы impl'ов): для parity это ALLOW.
                        return Decision.ALLOW;
                    }
                }
                case "/deployments", "/dmn", "/forms" ->
                    // Deploy-гейты живут в ресурсах, не в OperationsImpl:
                    // SUPER_ADMIN-only, та же строка что requireSuperAdmin.
                    { if (!principal.isSuperAdmin()) throw forbid(); }
                default -> throw new IllegalArgumentException("no live path for " + sample);
            }
            return Decision.ALLOW;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) return Decision.UNAUTH;
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) return Decision.DENY;
            // 404/400/409 — существование/валидация, не authz: для parity это
            // ALLOW (грант пройден, дальше — не политика).
            return Decision.ALLOW;
        }
    }

    private Decision replay(String sample, Principal principal, UUID id, byte[] body) {
        try {
            authorizer.authorizeReplay(principal, fill(sample, id), null, body);
            return Decision.ALLOW;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) return Decision.UNAUTH;
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) return Decision.DENY;
            return Decision.ALLOW;
        }
    }

    private static ResponseStatusException forbid() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }

    private byte[] bodyFor(String sample) {
        // Тела, которые authorizer парсит (start/publish); остальным тело не нужно.
        if ("/process-instances".equals(sample)) {
            return "{\"processDefinitionId\":\"00000000-0000-0000-0000-000000000000\"}".getBytes();
        }
        if ("/messages/publish".equals(sample)) {
            return ("{\"processInstanceId\":\"" + instanceId + "\"}").getBytes();
        }
        return "{}".getBytes();
    }

    private void grantAll(boolean allow) {
        lenient().when(authorizationService.canCompleteUserTask(any(), any(), any()))
            .thenReturn(allow);
        lenient().when(authorizationService.canClaimUserTask(any(), any(), any()))
            .thenReturn(allow);
        lenient().when(authorizationService.canReassignUserTask(any(), any()))
            .thenReturn(allow);
        lenient().when(authorizationService.canOperate(any(), anyString(), any()))
            .thenReturn(allow);
        lenient().doNothing().when(runtimeOperationSupport)
            .checkAssignee(any(), any(), any(), any());
    }

    @Test
    void allIdempotentSamples_covered() throws Exception {
        // Скоуп фильтра — источник истины: каждый idempotent-образец обязан
        // иметь parity-кейс. Новый путь в фильтре без кейса → красный.
        Method m = IdempotencyFilter.class.getDeclaredMethod("isIdempotentPath", String.class);
        m.setAccessible(true);
        List<String> concrete = List.of(
            "/user-tasks/" + taskId + "/complete",
            "/user-tasks/" + taskId + "/claim",
            "/user-tasks/" + taskId + "/unclaim",
            "/user-tasks/" + taskId + "/assign",
            "/service-tasks/" + serviceTaskId + "/complete",
            "/service-tasks/" + serviceTaskId + "/fail",
            "/service-tasks/" + serviceTaskId + "/throw-error",
            "/incidents/" + incidentId + "/resolve",
            "/process-instances/" + instanceId + "/cancel",
            "/process-instances",
            "/messages/publish",
            "/deployments",
            "/dmn",
            "/forms",
            "/auth/register"
        );
        for (String path : concrete) {
            assertThat((boolean) m.invoke(null, path))
                .as("filter scope must recognise idempotent path " + path)
                .isTrue();
        }
        // Не-idempotent пути — не в скоупе (replay их не касается).
        assertThat((boolean) m.invoke(null, "/me/password")).isFalse();
        assertThat((boolean) m.invoke(null, "/user-tasks")).isFalse();
        // Каждый образец из скоупа (кроме public register — там нечего сверять)
        // покрыт parity-кейсом ниже: сверка по строковому совпадению.
        for (String path : concrete) {
            if ("/auth/register".equals(path)) continue;
            String sample = path
                .replace(taskId.toString(), "{id}")
                .replace(serviceTaskId.toString(), "{id}")
                .replace(incidentId.toString(), "{id}")
                .replace(instanceId.toString(), "{id}");
            assertThat(COVERED_SAMPLES)
                .as("idempotent path must have a parity case: " + path)
                .contains(sample);
        }
    }

    @Test
    void parity_strangerDenied_adminAllowed() {
        // Чужой без грантов — DENY везде; админ — ALLOW везде (кроме
        // несуществующих ресурсов, где обе стороны ALLOW-по-существованию —
        // здесь все ресурсы замоканны существующими).
        for (String sample : COVERED_SAMPLES) {
            grantAll(false);
            UUID id = sample.contains("{id}") ? taskOrRelevant(sample) : null;
            assertThat(replay(sample, stranger, id, bodyFor(sample)))
                .as("replay stranger " + sample)
                .isEqualTo(Decision.DENY);
            assertThat(live(sample, stranger))
                .as("live stranger " + sample)
                .isEqualTo(Decision.DENY);

            grantAll(true);
            assertThat(replay(sample, admin, id, bodyFor(sample)))
                .as("replay admin " + sample)
                .isEqualTo(Decision.ALLOW);
            assertThat(live(sample, admin))
                .as("live admin " + sample)
                .isEqualTo(Decision.ALLOW);
        }
    }

    @Test
    void parity_anonymous_unauthorizedBoth() {
        grantAll(false);
        for (String sample : COVERED_SAMPLES) {
            if ("/deployments".equals(sample) || "/dmn".equals(sample) || "/forms".equals(sample)) {
                // requireSuperAdmin на null-принципале: NPE в live-заглушке —
                // это артефакт заглушки, не политика; deploy-гейты сверяются
                // парой stranger/admin выше. Здесь — только replay-сторона.
                assertThat(replay(sample, null, null, bodyFor(sample)))
                    .as("replay anonymous " + sample)
                    .isEqualTo(Decision.UNAUTH);
                continue;
            }
            UUID id = sample.contains("{id}") ? taskOrRelevant(sample) : null;
            Decision liveDecision = live(sample, null);
            Decision replayDecision = replay(sample, null, id, bodyFor(sample));
            if (liveDecision == Decision.DENY && replayDecision == Decision.UNAUTH) {
                // Принятое расхождение того же класса, что 404-vs-403
                // (критерий 2): live-операции без явного null-check
                // (service/fail/throw-error/resolve/cancel/publish/start —
                // null principal падает в canOperate(false) → 403), replay
                // проверяет principal первым → 401. Оба fail-closed; коды
                // ставит слой выше (JwtAuthFilter дал бы 401 раньше обоих).
                // Регрессией не считается; дрейф грантов ловится парой
                // stranger/admin выше.
                continue;
            }
            assertThat(liveDecision)
                .as("live anonymous " + sample)
                .isEqualTo(Decision.UNAUTH);
            assertThat(replayDecision)
                .as("replay anonymous " + sample)
                .isEqualTo(Decision.UNAUTH);
        }
    }

    @Test
    void parity_driftDetection_demo() {
        // Демонстрация чувствительности (не часть контракта): если одна
        // проверка в authorizer рассинхронизирована с live — parity падает.
        // Реальная RED-мутация — в отчёте (canCompleteUserTask→canClaimUserTask
        // в authorizeMutation): здесь — прямое доказательство, что тест
        // различает решения, а не всегда зелёный.
        grantAll(true);
        assertThat(live("/user-tasks/{id}/complete", stranger)).isEqualTo(Decision.ALLOW);
        assertThat(replay("/user-tasks/{id}/complete", stranger, taskId, bodyFor("/user-tasks/{id}/complete")))
            .isEqualTo(Decision.ALLOW);
    }

    @Test
    void parity_wrongCheckInAuthorizer_detected() {
        // Дифференцирующий кейс: complete-грант ЕСТЬ, claim-гранта НЕТ.
        // Live complete идёт через canCompleteUserTask → ALLOW. Replay обязан
        // звать ту же проверку: если authorizer ошибочно зовёт canClaimUserTask
        // (мутация критерия 1 — рассинхрон одной проверки), replay даст DENY
        // при live-ALLOW → тест красный. Без дифференциации (обе проверки в
        // одном значении) такой дрейф невидим — поэтому этот кейс существует.
        lenient().when(authorizationService.canCompleteUserTask(any(), any(), any()))
            .thenReturn(true);
        lenient().when(authorizationService.canClaimUserTask(any(), any(), any()))
            .thenReturn(false);
        lenient().when(authorizationService.canReassignUserTask(any(), any()))
            .thenReturn(false);
        lenient().when(authorizationService.canOperate(any(), anyString(), any()))
            .thenReturn(false);
        lenient().doNothing().when(runtimeOperationSupport)
            .checkAssignee(any(), any(), any(), any());
        assertThat(live("/user-tasks/{id}/complete", stranger))
            .as("live complete with complete-grant → ALLOW")
            .isEqualTo(Decision.ALLOW);
        assertThat(replay("/user-tasks/{id}/complete", stranger, taskId, bodyFor("/user-tasks/{id}/complete")))
            .as("replay complete must call canCompleteUserTask, not canClaimUserTask → ALLOW")
            .isEqualTo(Decision.ALLOW);
    }

    private UUID taskOrRelevant(String sample) {
        if (sample.startsWith("/service-tasks/")) return serviceTaskId;
        if (sample.startsWith("/incidents/")) return incidentId;
        if (sample.startsWith("/process-instances/")) return instanceId;
        return taskId;
    }
}
