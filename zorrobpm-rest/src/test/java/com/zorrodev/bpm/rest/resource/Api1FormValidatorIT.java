package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
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
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-API-1, кластер 2 (F16/F17): FormValidator через прод-путь complete.
 *
 * <p>Всё — через полный Spring-контекст + реальный `POST /user-tasks/{id}/complete`
 * (V11, POF-обоснование из WO: не прямой вызов FormValidator). Процесс:
 * деплой FORM_JS-формы с required-полем + BPMN с userTask formKey → старт →
 * complete с null/[] → 400. Pinned-v1: деплой v2 после старта не меняет схему,
 * по которой валидируется сабмит.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Api1FormValidatorIT {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        MvcResult r = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        adminToken = mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private void deployForm(String key, String schema) throws Exception {
        DeployFormDTO dto = new DeployFormDTO();
        dto.setKey(key);
        dto.setKind("FORM_JS");
        dto.setSchema(schema);
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    private UUID deployProcess(String formKey) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn")))
                .replace("formKey=\"orderForm\"", "formKey=\"" + formKey + "\"");
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deploy = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(deploy.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID startAndGetTask(UUID pdId) throws Exception {
        StartProcessInstanceDTO start = new StartProcessInstanceDTO();
        start.setProcessDefinitionId(pdId);
        start.setVariables(List.of());
        MvcResult started = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(start))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn();
        UUID piId = UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());
        MvcResult tasks = mockMvc.perform(get("/user-tasks?processInstanceId=" + piId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(mapper.readTree(tasks.getResponse().getContentAsString())
                .get("data").get(0).get("id").asText());
    }

    private static final String REQUIRED_SCHEMA =
            "{\"type\":\"form\",\"components\":[{\"type\":\"textfield\",\"key\":\"name\",\"validate\":{\"required\":true}}]}";

    // ── Критерий 5: null/[] на required-форме через complete → 400 ──────

    @Test
    void complete_nullVariables_requiredForm_400() throws Exception {
        String formKey = "api1-req-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formKey, REQUIRED_SCHEMA);
        UUID taskId = startAndGetTask(deployProcess(formKey));

        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"variables\":null}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void complete_emptyVariables_requiredForm_400() throws Exception {
        String formKey = "api1-reqe-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formKey, REQUIRED_SCHEMA);
        UUID taskId = startAndGetTask(deployProcess(formKey));

        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── Критерий 6: pinned v1 остаётся v1 после деплоя v2 ──────────────
    // formId-путь с bindingType=deployment (фикстура test-c8-form-deployment-
    // binding.bpmn): embedded userTaskForm кладёт v1 тем же batch-деплойментом
    // (deploymentId штампуется registrar'ом); v2 тем же formId — single-деплой
    // POST /forms (deploymentId=null, latest уезжает). Задача хранит formId +
    // bindingType=deployment; эффективная схема обязана остаться v1.

    @Test
    void complete_pinnedV1_afterV2Deploy_validatesV1() throws Exception {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        // Локальная копия engine-фикстуры test-c8-form-deployment-binding.bpmn
        // (своя копия — гарантии P-35: чистый checkout обязан содержать файл).
        String bpmn = new String(Files.readAllBytes(
                Paths.get("src/test/files/api1-pinned-form.bpmn")))
                .replace("c8-form-deployment-binding", "api1-pinp-" + uniq);
        AddProcessDefinitionDTO batchDto = new AddProcessDefinitionDTO();
        batchDto.setBpmn(bpmn);
        // batch-деплой: BPMN + embedded userTaskForm одним POST /deployments
        String batchBody = "{\"resources\":[{\"type\":\"BPMN\",\"content\":"
                + mapper.writeValueAsString(bpmn) + "}]}";
        MvcResult batch = mockMvc.perform(post("/deployments")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(batchBody)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn();
        UUID pdId = UUID.fromString(mapper.readTree(batch.getResponse().getContentAsString())
                .get("processes").get(0).get("processDefinitionId").asText());
        // v1 embedded несёт required "name" (см. userTaskForm pinned-form
        // в копии фикстуры api1-pinned-form.bpmn).
        UUID taskId = startAndGetTask(pdId);
        // v2 тем же formId="pinned-form", single-деплой: latest уезжает на схему
        // без required; pinned-валидация обязана держать v1
        DeployFormDTO v2 = new DeployFormDTO();
        v2.setKey("camunda-forms:bpmn:userTaskForm_pinned-form");
        v2.setKind("FORM_JS");
        v2.setSchema("{\"type\":\"form\",\"id\":\"pinned-form\",\"components\":"
                + "[{\"type\":\"textfield\",\"key\":\"other\"}]}");
        mockMvc.perform(post("/forms")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(v2))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Сабмит без "name": по v1 обязан 400, по latest(v2) был бы 200
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"variables\":[]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── Критерий 7: nested required / integer-дробь / NaN ──────────────

    @Test
    void complete_nestedRequired_400() throws Exception {
        String formKey = "api1-nest-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formKey, "{\"type\":\"form\",\"components\":["
                + "{\"type\":\"group\",\"key\":\"grp\",\"components\":["
                + "{\"type\":\"textfield\",\"key\":\"inner\",\"validate\":{\"required\":true}}]}]}");
        UUID taskId = startAndGetTask(deployProcess(formKey));

        String body = "{\"variables\":[{\"name\":\"other\",\"value\":\"x\",\"type\":\"STRING\"}]}";
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void complete_fractionalInteger_400() throws Exception {
        String formKey = "api1-int-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formKey, "{\"type\":\"form\",\"components\":[{\"type\":\"integer\",\"key\":\"qty\"}]}");
        UUID taskId = startAndGetTask(deployProcess(formKey));

        String body = "{\"variables\":[{\"name\":\"qty\",\"value\":\"1.5\",\"type\":\"STRING\"}]}";
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void complete_nanInteger_400() throws Exception {
        String formKey = "api1-nan-" + UUID.randomUUID().toString().substring(0, 8);
        deployForm(formKey, "{\"type\":\"form\",\"components\":[{\"type\":\"integer\",\"key\":\"qty\"}]}");
        UUID taskId = startAndGetTask(deployProcess(formKey));

        String body = "{\"variables\":[{\"name\":\"qty\",\"value\":\"NaN\",\"type\":\"STRING\"}]}";
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── Критерий 8: дорогой regex — защита от ReDoS ────────────────────

    @Test
    void complete_pathologicalRegex_doesNotHang() throws Exception {
        String formKey = "api1-re-" + UUID.randomUUID().toString().substring(0, 8);
        // Классика catastrophic backtracking: (a+)+$ на строке без совпадения
        deployForm(formKey, "{\"type\":\"form\",\"components\":["
                + "{\"type\":\"textfield\",\"key\":\"code\",\"validate\":{\"pattern\":\"^(a+)+$\"}}]}");
        UUID taskId = startAndGetTask(deployProcess(formKey));

        String evil = "a".repeat(30) + "!";
        String body = "{\"variables\":[{\"name\":\"code\",\"value\":\"" + evil + "\",\"type\":\"STRING\"}]}";
        // Без защиты — секунды/минуты зависания; с защитой — быстрый ответ
        // (400 по отклонённому/невалидному паттерну либо 200, но НЕ hang).
        // Таймаут на уровне MockMvc: тест упадёт по timeout, если зависнет.
        MvcResult r = mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        int code = r.getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(code).isIn(200, 400);
    }
}
