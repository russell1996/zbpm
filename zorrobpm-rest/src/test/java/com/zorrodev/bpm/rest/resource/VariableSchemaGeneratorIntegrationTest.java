package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VariableSchemaGeneratorIntegrationTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

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
        adminToken = authResponse.getToken();
    }

    // ── criterion #1: generates valid JSON Schema 2020-12 ──

    @Test
    void criterion1_generatesValidJsonSchema202012() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("name");
        f.setLabel("Name");
        f.setType("string");
        f.setRequired(true);
        f.setMaxLength(100);

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").isNotEmpty())
                .andReturn();

        GeneratedSchemaDTO resp = mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class);
        JsonNode schema = mapper.readTree(resp.getSchema());
        assertThat(schema.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").has("name")).isTrue();
    }

    // ── criterion #2: type mapping ──

    @Test
    void criterion2_typeMapping_string() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("f");
        f.setType("string");
        f.setMaxLength(50);
        f.setPattern("^[a-z]+$");

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schema = mapper.readTree(mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class).getSchema());
        assertThat(schema.get("properties").get("f").get("type").asText()).isEqualTo("string");
        assertThat(schema.get("properties").get("f").get("maxLength").asInt()).isEqualTo(50);
        assertThat(schema.get("properties").get("f").get("pattern").asText()).isEqualTo("^[a-z]+$");
    }

    @Test
    void criterion2_typeMapping_number() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("amt");
        f.setType("number");
        f.setMin(0);
        f.setMax(1000);

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schema = mapper.readTree(mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class).getSchema());
        assertThat(schema.get("properties").get("amt").get("type").asText()).isEqualTo("number");
        assertThat(schema.get("properties").get("amt").get("minimum").asInt()).isEqualTo(0);
        assertThat(schema.get("properties").get("amt").get("maximum").asInt()).isEqualTo(1000);
    }

    @Test
    void criterion2_typeMapping_integer() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("count");
        f.setType("integer");

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schema = mapper.readTree(mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class).getSchema());
        assertThat(schema.get("properties").get("count").get("type").asText()).isEqualTo("integer");
    }

    @Test
    void criterion2_typeMapping_boolean() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("active");
        f.setType("boolean");

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schema = mapper.readTree(mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class).getSchema());
        assertThat(schema.get("properties").get("active").get("type").asText()).isEqualTo("boolean");
    }

    @Test
    void criterion2_typeMapping_date() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("date");
        f.setType("date");

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schema = mapper.readTree(mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class).getSchema());
        assertThat(schema.get("properties").get("date").get("type").asText()).isEqualTo("string");
        assertThat(schema.get("properties").get("date").get("format").asText()).isEqualTo("date");
    }

    @Test
    void criterion2_typeMapping_datetime() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("dt");
        f.setType("datetime");

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schema = mapper.readTree(mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class).getSchema());
        assertThat(schema.get("properties").get("dt").get("type").asText()).isEqualTo("string");
        assertThat(schema.get("properties").get("dt").get("format").asText()).isEqualTo("date-time");
    }

    @Test
    void criterion2_typeMapping_array() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("tags");
        f.setType("array");
        f.setItemsType("string");
        f.setMinItems(1);

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schema = mapper.readTree(mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class).getSchema());
        assertThat(schema.get("properties").get("tags").get("type").asText()).isEqualTo("array");
        assertThat(schema.get("properties").get("tags").get("items").get("type").asText()).isEqualTo("string");
        assertThat(schema.get("properties").get("tags").get("minItems").asInt()).isEqualTo(1);
    }

    // ── criterion #3: required + enum ──

    @Test
    void criterion3_requiredAndEnum() throws Exception {
        FieldDTO f1 = new FieldDTO();
        f1.setKey("status");
        f1.setType("string");
        f1.setRequired(true);
        f1.setEnumValues(List.of("ACTIVE", "INACTIVE"));

        FieldDTO f2 = new FieldDTO();
        f2.setKey("notes");
        f2.setType("string");

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f1, f2));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schema = mapper.readTree(mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class).getSchema());
        assertThat(schema.get("required").size()).isEqualTo(1);
        assertThat(schema.get("required").get(0).asText()).isEqualTo("status");
        assertThat(schema.get("properties").get("status").get("enum").size()).isEqualTo(2);
        assertThat(schema.get("properties").get("status").get("enum").get(0).asText()).isEqualTo("ACTIVE");
        assertThat(schema.get("properties").get("status").get("enum").get(1).asText()).isEqualTo("INACTIVE");
    }

    // ── criterion #4: x-builder round-trip (POF §1b) ──

    @Test
    void criterion4_xBuilderRoundTrip() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("senderNumber");
        f.setLabel("Исходящий номер");
        f.setType("string");
        f.setRequired(true);
        f.setMaxLength(50);
        f.setPattern("^\\d+$");

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        MvcResult result = mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode schema = mapper.readTree(mapper.readValue(result.getResponse().getContentAsString(), GeneratedSchemaDTO.class).getSchema());
        assertThat(schema.has("x-builder")).isTrue();
        assertThat(schema.get("x-builder").has("fields")).isTrue();
        JsonNode fields = schema.get("x-builder").get("fields");
        assertThat(fields.size()).isEqualTo(1);
        assertThat(fields.get(0).get("key").asText()).isEqualTo("senderNumber");
        assertThat(fields.get(0).get("label").asText()).isEqualTo("Исходящий номер");
        assertThat(fields.get(0).get("type").asText()).isEqualTo("string");
        assertThat(fields.get(0).get("required").asBoolean()).isTrue();
        assertThat(fields.get(0).get("maxLength").asInt()).isEqualTo(50);
        assertThat(fields.get(0).get("pattern").asText()).isEqualTo("^\\d+$");
    }

    // ── criterion #5: bad input → 400 ──

    @Test
    void criterion5_emptyFields_returns400() throws Exception {
        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of());

        mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void criterion5_nullFields_returns400() throws Exception {
        GenerateSchemaDTO dto = new GenerateSchemaDTO();

        mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void criterion5_blankKey_returns400() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("");
        f.setType("string");

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void criterion5_unknownType_returns400() throws Exception {
        FieldDTO f = new FieldDTO();
        f.setKey("x");
        f.setType("unknown");

        GenerateSchemaDTO dto = new GenerateSchemaDTO();
        dto.setFields(List.of(f));

        mockMvc.perform(post("/variable-schemas/generate")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
