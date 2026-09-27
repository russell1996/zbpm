package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-INT-9 (V10-b): брокер недоступен в момент синка для provisioned-юзера —
 * membership-транзакция падает целиком (fail-closed, 503), а не коммитит DB
 * с дрейфом прав. Base-url указывает в закрытый порт (connection refused),
 * флаг выставлен напрямую через репозиторий (брокер-аккаунт «был», теперь
 * брокер лёг — ровно сценарий даунтайма из решения CTO).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "zorrobpm.rabbitmq.management.base-url=http://127.0.0.1:9")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitMqProvisioningFailClosedIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired com.zorrodev.bpm.engine.repository.ProcessRepository processRepository;
    @Autowired com.zorrodev.bpm.engine.repository.ProcessMemberRepository processMemberRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void brokerDown_addMemberForProvisionedUser_rollsBackWith503() throws Exception {
        String superAdminToken = loginAndGetToken("admin", "admin");
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        UUID userId = createSystemUser("int9-fc-" + uniq, true);
        String processKey = deployJobProcess("int9-fc-proc", "int9fcjob" + uniq);

        MvcResult add = mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"OWNER\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(add.getResponse().getStatus())
            .as("broker down → membership must fail closed, not commit without rights")
            .isEqualTo(503);

        // Транзакция откатилась целиком: строки членства нет в БД
        // (иначе юзер состоял бы в процессе БЕЗ broker-прав — дрейф).
        UUID processId = processRepository.findByDefinitionKey(processKey).orElseThrow().getId();
        assertThat(processMemberRepository.findByProcessId(processId))
            .as("rolled-back membership must leave no row")
            .noneMatch(m -> m.getUserId().equals(userId));
    }

    @Test
    void brokerDown_provisionItself_503AndFlagUnset() throws Exception {
        String superAdminToken = loginAndGetToken("admin", "admin");
        UUID userId = createSystemUser("int9-fcp-" + UUID.randomUUID().toString().substring(0, 8), false);

        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/rabbitmq-password")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andReturn();

        assertThat(result.getResponse().getStatus())
            .as("broker down → provision must fail closed").isEqualTo(503);
        assertThat(userRepository.findById(userId).orElseThrow().isRabbitmqProvisioned())
            .as("flag must NOT be set when the broker call failed").isFalse();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private UUID createSystemUser(String username, boolean provisioned) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setUserType("SYSTEM");
        user.setRabbitmqProvisioned(provisioned);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private String deployJobProcess(String keyPrefix, String jobType) throws Exception {
        String superAdminToken = loginAndGetToken("admin", "admin");
        String key = (keyPrefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toLowerCase();
        String bpmn = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("src/test/files/sec43-process.bpmn")));
        bpmn = bpmn.replace("id=\"sec43-process\"", "id=\"" + key + "\"")
            .replace("name=\"SEC43 Process\"", "name=\"" + key + "\"")
            .replace("bpmnElement=\"sec43-process\"", "bpmnElement=\"" + key + "\"")
            .replace("type=\"serviceTask1\"", "type=\"" + jobType + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        return key;
    }
}
