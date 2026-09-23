package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-3 integration tests:
 *  #1: POST /process-instances without id AND key → 400 VALIDATION_ERROR
 *  #2: POST /process-instances by key (without id) → works (regression)
 *  #3: GET /user-tasks/{id} non-existent UUID → 404 NOT_FOUND
 *  #5: GET /process-definitions/{id}/xml non-existent → 404 NOT_FOUND
 *  #6: message is not empty in error response
 *  #7: proof-of-failure was RED (500), now GREEN (404)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String token;

    @BeforeAll
    void login() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        token = authResponse.getToken();
    }

    // --- Criterion #1: POST /process-instances without id AND key → 400 ---

    @Test
    void criterion1_startProcess_noIdNoKey_returns400() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        // Both processDefinitionId and processDefinitionKey are null

        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors", hasSize(1)));
    }

    // --- Criterion #2: POST /process-instances by key → works (regression) ---

    @Test
    void criterion2_startProcess_byKey_works() throws Exception {
        // Deploy process1.bpmn first (test must be self-contained — P-8)
        String bpmn = Files.readString(Paths.get("src/test/files/process1.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Now start process by key "process1" → must return 200
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("process1");

        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(notNullValue()));
    }

    // --- Criterion #3: GET /user-tasks/{id} non-existent → 404 ---

    @Test
    void criterion3_getUserTask_nonExistent_returns404() throws Exception {
        UUID fakeId = UUID.randomUUID();
        mockMvc.perform(get("/user-tasks/" + fakeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(notNullValue()));
    }

    // --- Criterion #5: GET /process-definitions/{id}/xml non-existent → 404 ---

    @Test
    void criterion5_getProcessDefinitionXml_nonExistent_returns404() throws Exception {
        UUID fakeId = UUID.randomUUID();
        mockMvc.perform(get("/process-definitions/" + fakeId + "/xml")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- Criterion #6: message is not empty ---

    @Test
    void criterion6_errorResponse_hasMessage() throws Exception {
        UUID fakeId = UUID.randomUUID();
        mockMvc.perform(get("/user-tasks/" + fakeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
