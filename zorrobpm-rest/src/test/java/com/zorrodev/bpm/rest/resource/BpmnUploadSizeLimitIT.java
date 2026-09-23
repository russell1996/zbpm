package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-62: BPMN upload size limit (DoS).
 * {@code POST /process-definitions} with a payload over 5M chars must answer
 * 413 BPMN_TOO_LARGE (not OOM/500); a valid small BPMN still deploys; the
 * service layer enforces the same cap for direct callers.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BpmnUploadSizeLimitIT {

    @Autowired MockMvc mockMvc;
    @Autowired ProcessDefinitionService processDefinitionService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String validBpmn;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        validBpmn = Files.readString(Paths.get("src/test/files/process1.bpmn"));
    }

    @Test
    void oversizedBpmn_rejected413() throws Exception {
        String oversized = "x".repeat(AddProcessDefinitionDTO.MAX_BPMN_LENGTH + 1);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(oversized);

        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("BPMN_TOO_LARGE"))
            .andExpect(jsonPath("$.params.maxLength").value(AddProcessDefinitionDTO.MAX_BPMN_LENGTH));
    }

    @Test
    void oversizedBpmnOnVersionEndpoint_rejected413() throws Exception {
        String oversized = "y".repeat(AddProcessDefinitionDTO.MAX_BPMN_LENGTH + 1);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(oversized);

        mockMvc.perform(post("/process-definitions/{id}/versions", java.util.UUID.randomUUID())
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("BPMN_TOO_LARGE"));
    }

    @Test
    void serviceDirectCall_oversized_throws413() {
        String oversized = "z".repeat(AddProcessDefinitionDTO.MAX_BPMN_LENGTH + 1);
        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(oversized))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> {
                ApiException api = (ApiException) e;
                assertThat(api.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                assertThat(api.getCode()).isEqualTo("BPMN_TOO_LARGE");
            });
    }

    @Test
    void validBpmn_stillDeploys() throws Exception {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(validBpmn);

        MvcResult result = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("key").asText())
            .isNotBlank();
    }

    private String login(String username, String password) throws Exception {
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
}
