package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-QW-3 (N12): cross-origin clients must be able to use idempotency —
 * a preflight carrying {@code Idempotency-Key} in
 * {@code Access-Control-Request-Headers} has to pass against an allowed
 * origin, and a real POST carrying the header must come back with CORS
 * headers so the browser delivers the response to JS. Before the fix
 * {@code allowedHeaders} did not list the header, so the preflight answer
 * did not echo it and browsers refused to send it.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class IdempotencyKeyCorsIT {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void preflight_withIdempotencyKey_allowedOrigin_echoesHeader() throws Exception {
        mockMvc.perform(options("/user-tasks/some-id/complete")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type,Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Idempotency-Key")));
    }

    @Test
    void preflight_forbiddenOrigin_stillNoAllowHeader() throws Exception {
        mockMvc.perform(options("/user-tasks/some-id/complete")
                        .header("Origin", "http://evil.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Idempotency-Key"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void actualPost_withIdempotencyKey_allowedOrigin_carriesCorsHeaders() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        mockMvc.perform(post("/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Idempotency-Key", "qw3-cors-probe")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
