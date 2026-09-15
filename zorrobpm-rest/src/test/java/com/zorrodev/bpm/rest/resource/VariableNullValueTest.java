package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-23: variable value=null should not produce 500.
 * Null value is normalized to "" at write time (Option A).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VariableNullValueTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        // Create admin user and login
        UiUserEntity admin = new UiUserEntity();
        admin.setId(UUID.randomUUID());
        admin.setUsername("sec23admin");
        admin.setPasswordHash(passwordHasher.hash("admin"));
        admin.setFullName("admin");
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setCreatedAt(Instant.now());
        admin.setUpdatedAt(Instant.now());
        userRepository.save(admin);

        LoginDTO loginDto = new LoginDTO();
        loginDto.setUsername("sec23admin");
        loginDto.setPassword("admin");
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        adminToken = mapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class).getToken();

        // Deploy BPMN
        String bpmn = Files.readString(Paths.get("src/test/files/assignee-task.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        processDefinitionId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());
    }

    // --- POF: POST with value=null → NOT 500 ---

    @Test
    void nullValue_doesNotReturn500() throws Exception {
        ProcessVariable nullVar = new ProcessVariable();
        nullVar.setName("testVar");
        nullVar.setValue(null);
        nullVar.setType(ProcessVariableType.STRING);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(List.of(nullVar));

        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    void nullValue_normalizedToEmptyString() throws Exception {
        ProcessVariable nullVar = new ProcessVariable();
        nullVar.setName("testVar");
        nullVar.setValue(null);
        nullVar.setType(ProcessVariableType.STRING);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(List.of(nullVar));

        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();

        UUID piId = UUID.fromString(
            mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        // Read back the variable
        MvcResult varResult = mockMvc.perform(get("/variables?processInstanceId=" + piId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        // Variable should exist with value ""
        String varBody = varResult.getResponse().getContentAsString();
        assertThat(varBody).contains("\"testVar\"");
        assertThat(varBody).contains("\"value\":\"\"");
    }
}
