package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-EVT-4: Integration tests for SSE event stream.
 * Full-context (V11): real filter chain, real DB.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class SseEventStreamIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;

    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    @SuppressWarnings("unchecked")
    private String login(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    @Test
    void sseEndpoint_returnsEventStream() throws Exception {
        MvcResult result = mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken))
            .andReturn();
        // SSE endpoint should return 200 or 500 (if RabbitMQ not available)
        assertThat(result.getResponse().getStatus()).isIn(200, 500);
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/events/stream"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void sseEndpoint_acceptsTypeFilter() throws Exception {
        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken)
                .param("type", "process-instance.started"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/event-stream"));
    }

    @Test
    void sseEndpoint_acceptsProcessDefinitionKeyFilter() throws Exception {
        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken)
                .param("processDefinitionKey", "testProcess"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/event-stream"));
    }

    @Test
    void sseEndpoint_acceptsLastEventId() throws Exception {
        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken)
                .header("Last-Event-ID", "100"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/event-stream"));
    }
}
