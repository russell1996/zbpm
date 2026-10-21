package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-OPS-38: операторская отмена инстанса ({@code POST /process-instances/{id}
 * /cancel} → {@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance})
 * обязана снимать signal-подписки инстанса.
 *
 * <p>Раньше этот путь снимал ТОЛЬКО message-подписки: signal-подписки
 * отменённого инстанса оставались, broadcast сигнала уходил в мёртвый инстанс,
 * а инвариант утечек (G-P) видел остаток типа «subscription».
 *
 * <p>Тест идёт через БОЕВОЙ HTTP-путь (V11): деплой → старт → отмена — всё
 * настоящими запросами; факт подписок читается из БД (JdbcTemplate), а не из
 * ответа эндпоинта (его нет — правило про подписки внутреннее).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelProcessInstanceSubscriptionCleanupIT {

    private static final String PROCESS_KEY = "test-ops38-cancelled-subscriptions";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignalSubscriptionRepository signalSubscriptionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String token;
    /** Инстанс, поднятый этим тестом (для самоочистки). */
    private UUID ownInstance;

    @BeforeAll
    void login() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        token = authResponse.getToken();
    }

    private void deploy() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-ops38-cancelled-subscriptions.bpmn"));
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    private UUID start() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey(PROCESS_KEY);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    /**
     * Факт БД: строки signal-подписок инстанса (все, независимо от consumed).
     * Строки — читаемым текстом, чтобы падение показывало, ЧТО осталось.
     */
    private List<String> signalSubscriptions(UUID pi) {
        return signalSubscriptionRepository.findAll().stream()
            .filter(s -> pi.equals(s.getProcessInstanceId()))
            .map(s -> s.getSignalName() + "/consumed=" + s.isConsumed()
                + "/activity=" + (s.getActivityId() == null ? "-" : s.getActivityId()))
            .sorted()
            .toList();
    }

    /**
     * Самоочистка: общая H2-БД rest-модуля делится между 1400+ тестами, и остаток
     * этого теста не должен попадать в чужие выборки. Порядок — ОТ ЛИСТЬЕВ К КОРНЮ
     * (FK {@code user_tasks → process_instances} — RESTRICT: без удаления задач
     * удаление инстанса не проходит).
     *
     * <p>Задеплоенное ОПРЕДЕЛЕНИЕ остаётся: оно инертно (никакой тест rest-модуля не
     * считает определения по всему столу), а соседний тест операторской отмены
     * ({@code CancelProcessInstanceTest}) оставляет своё определение так же.
     */
    @AfterEach
    void cleanOwnInstance() {
        if (ownInstance == null) {
            return;
        }
        jdbc.update("DELETE FROM user_task_candidates WHERE user_task_id IN "
            + "(SELECT id FROM user_tasks WHERE process_instance_id = ?)", ownInstance);
        jdbc.update("DELETE FROM user_tasks WHERE process_instance_id = ?", ownInstance);
        jdbc.update("DELETE FROM service_tasks WHERE process_instance_id = ?", ownInstance);
        jdbc.update("DELETE FROM variables WHERE process_instance_id = ?", ownInstance);
        jdbc.update("DELETE FROM activities WHERE process_instance_id = ?", ownInstance);
        jdbc.update("DELETE FROM process_instances WHERE id = ?", ownInstance);
        ownInstance = null;
    }

    @Test
    void operatorCancel_dropsSignalSubscriptionsOfTheCancelledInstance() throws Exception {
        deploy();
        UUID pi = start();
        ownInstance = pi;

        assertThat(signalSubscriptions(pi))
            .as("премисса: инстанс вооружён signal-подписками (граница + event-subprocess)")
            .hasSizeGreaterThanOrEqualTo(2);

        mockMvc.perform(post("/process-instances/" + pi + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(pi.toString()));

        assertThat(signalSubscriptions(pi))
            .as("отменённый оператором инстанс не оставляет signal-подписок")
            .isEmpty();
    }
}
