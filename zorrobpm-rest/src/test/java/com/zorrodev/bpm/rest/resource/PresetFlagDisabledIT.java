package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-VT-1 п.6: при {@code zorrobpm.ui.variable-presets.enabled=false} весь
 * REST {@code /presets/**} отдаёт 404 аутентифицированным — даже SUPER_ADMIN
 * (флаг первее сервисной логики, см. {@code PresetResource.requireEnabled}).
 *
 * <p>Аноним — 401, а НЕ 404 (раунд 2, Б-4): JwtAuthFilter стоит раньше
 * контроллера и отвечает отказом до проверки флага. Прежняя формулировка
 * «404 для всех, включая анонимов» была ложной — этот класс теперь пинает
 * оба факта.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "zorrobpm.ui.variable-presets.enabled=false")
@AutoConfigureMockMvc
class PresetFlagDisabledIT {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void flagOff_everyPresetEndpoint_is404() throws Exception {
        String adminToken = login("admin", "admin");
        mockMvc.perform(get("/presets?key=x")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"processDefinitionKey\":\"x\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void flagOff_anonymousGets401_not404() throws Exception {
        // Б-4: аноним не доходит до флага — JwtAuthFilter раньше контроллера.
        mockMvc.perform(get("/presets?key=x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/presets")
                        .content("{\"processDefinitionKey\":\"x\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
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
