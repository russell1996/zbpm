package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REL-32 F04/F05: auth before replay, actor_id not credential hash.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class Rel32F04F05IT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    private String login(String u, String p) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(u);
        dto.setPassword(p);
        MvcResult r = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private UUID deployProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn")))
            .replace("id=\"form-process\"", "id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult r = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void f04_revokeBetweenRequests_returns401NotReplay() throws Exception {
        String procKey = "rel32-f04-" + UUID.randomUUID().toString().substring(0,4);
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();
        // first succeeds
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
        // create a temp user and revoke by deactivating
        String user = "rel32f04-" + UUID.randomUUID().toString().substring(0,4);
        UiUserEntity u = new UiUserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername(user);
        u.setPasswordHash(passwordHasher.hash("pass"));
        u.setFullName(user);
        u.setRole("USER");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        UUID uid = userRepository.save(u).getId();
        String userToken = login(user, "pass");
        String userKey = UUID.randomUUID().toString();
        String userBody = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        // need to give user OWNER on process to allow start
        mockMvc.perform(post("/processes/" + procKey + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + uid + "\",\"role\":\"OWNER\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", userKey)
                .content(userBody).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
        // deactivate user
        u.setActive(false);
        userRepository.save(u);
        // replay with same key should be 401, not saved 201
        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", userKey)
                .content(userBody).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void f05_sameUserTwoTokens_oneEffect() throws Exception {
        String procKey = "rel32-f05-" + UUID.randomUUID().toString().substring(0,4);
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        // exp/сек: ждём смену секунды настенных часов вместо фикс-паузы (WO-OPS-14).
        long beforeSecond = Instant.now().getEpochSecond();
        await().atMost(java.time.Duration.ofSeconds(5))
            .until(() -> Instant.now().getEpochSecond() != beforeSecond);
        String rotated = login("admin", "admin");
        assertThat(rotated).isNotEqualTo(adminToken);
        MvcResult second = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + rotated)
                .header("Idempotency-Key", key)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        String id1 = mapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String id2 = mapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assertThat(id2).as("same actor two tokens → one effect").isEqualTo(id1);
    }

    @Test
    void f05_differentUsers_isolated() throws Exception {
        String procKey = "rel32-f05b-" + UUID.randomUUID().toString().substring(0,4);
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();
        MvcResult adminCall = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        String user = "rel32f05u-" + UUID.randomUUID().toString().substring(0,4);
        UiUserEntity u = new UiUserEntity();
        u.setId(UUID.randomUUID());
        u.setUsername(user);
        u.setPasswordHash(passwordHasher.hash("pass"));
        u.setFullName(user);
        u.setRole("USER");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        UUID uid = userRepository.save(u).getId();
        mockMvc.perform(post("/processes/" + procKey + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + uid + "\",\"role\":\"OWNER\"}"))
            .andExpect(status().isOk());
        String userToken = login(user, "pass");
        MvcResult userCall = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + userToken)
                .header("Idempotency-Key", key)
                .content(body).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        assertThat(mapper.readTree(userCall.getResponse().getContentAsString()).get("id").asText())
            .isNotEqualTo(mapper.readTree(adminCall.getResponse().getContentAsString()).get("id").asText());
    }
}
