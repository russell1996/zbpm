package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-AUDIT-14 (F-19): контракт Preset API.
 *
 * <p>Три дефекта, найденные аудитом:
 * <ul>
 *   <li><b>create/import отвечали 200 без {@code Location}</b> — клиент не мог
 *       перейти к созданному ресурсу по заголовку (в проекте это уже делает
 *       {@code ProcessSubmissionResource}: 201 + Location). Теперь 201 +
 *       {@code Location: /presets/{id}} — контракт (интерфейс) не тронут.</li>
 *   <li><b>{@code version} был {@code int}</b>: пропущенное поле биндилось в 0,
 *       и PUT без версии ПРОХОДИЛ оптимистическую блокировку на свежем
 *       пресете (version=0) — тихая потеря защиты от гонки правок. Теперь
 *       {@code Integer} + {@code @NotNull}: без версии — 400 VALIDATION_ERROR,
 *       а не «случайно совпало».</li>
 *   <li><b>{@code POST /presets} не был идемпотентным</b>: сетевой ретрай
 *       создавал дубль. Теперь {@code /presets*} в реестре фильтра
 *       идемпотентности (после F-16, который научил replay отдавать
 *       {@code Location}). Повтор с тем же ключом — тот же 201, тот же
 *       {@code Location}, один шаблон; а если права сузились — 403, а не
 *       устаревший успех.</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresetApiContractIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String ownerToken;
    private UUID ownerId;
    private String processKey;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        ownerId = createUser("audit14owner" + UUID.randomUUID().toString().substring(0, 6));
        ownerToken = login(userRepository.findById(ownerId).orElseThrow().getUsername(), "passr");

        String bpmn = Files.readString(
            Paths.get("src/test/files/assignee-task.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        processKey = mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText();

        // DESIGNER — роль, которой хватает права START (создание шаблона).
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"userId\":\"" + ownerId + "\",\"role\":\"DESIGNER\"}")
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    // ==================== 201 + Location ====================

    @Test
    void create_answers201WithLocationHeader() throws Exception {
        MvcResult result = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(createBody("loc-create"))
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        String id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(result.getResponse().getHeader("Location"))
            .as("create must answer 201 with the Location of the created preset")
            .isEqualTo("/presets/" + id);
    }

    @Test
    void import_answers201WithLocationHeader() throws Exception {
        MvcResult result = mockMvc.perform(post("/presets/import")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(createBody("loc-import"))
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        String id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(result.getResponse().getHeader("Location"))
            .as("import must answer 201 with the Location of the imported preset")
            .isEqualTo("/presets/" + id);
    }

    // ==================== version: Integer + @NotNull ====================

    @Test
    void update_withoutVersion_answers400ValidationError() throws Exception {
        MvcResult created = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(createBody("noversion"))
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        MvcResult result = mockMvc.perform(put("/presets/" + id)
                        .header("Authorization", "Bearer " + ownerToken)
                        .content("{\"description\":\"no version here\"}")
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andReturn();

        JsonNode error = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("code").asText())
            .as("a missing version is a BAD REQUEST, not a silent version=0")
            .isEqualTo("VALIDATION_ERROR");
        assertThat(error.get("errors").get(0).get("field").asText())
            .as("the rejection must name the field — the bean-validation shape of the "
                + "project's 400 envelope")
            .isEqualTo("version");
    }

    @Test
    void update_withVersion_answers200AndBumpsVersion() throws Exception {
        MvcResult created = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(createBody("withversion"))
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode body = mapper.readTree(created.getResponse().getContentAsString());
        String id = body.get("id").asText();
        int version = body.get("version").asInt();

        MvcResult result = mockMvc.perform(put("/presets/" + id)
                        .header("Authorization", "Bearer " + ownerToken)
                        .content("{\"description\":\"with version\",\"version\":" + version + "}")
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("version").asInt())
            .isEqualTo(version + 1);
    }

    // ==================== идемпотентность create ====================

    @Test
    void create_sameIdempotencyKey_replaysSame201AndLocation_andCreatesOnePreset() throws Exception {
        String key = UUID.randomUUID().toString();
        String presetName = "idem-" + UUID.randomUUID().toString().substring(0, 6);
        String body = createBody(presetName);

        MvcResult first = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", key)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        MvcResult replay = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", key)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        String firstLocation = first.getResponse().getHeader("Location");
        assertThat(firstLocation)
            .as("the original answer must carry a Location — otherwise 'the replay has "
                + "the same Location' would compare null with null")
            .isNotNull();
        assertThat(replay.getResponse().getHeader("Location"))
            .as("the replay must answer with the same Location (F-16)")
            .isEqualTo(firstLocation);
        assertThat(replay.getResponse().getContentAsString())
            .as("the replay must answer with the same body")
            .isEqualTo(first.getResponse().getContentAsString());
        assertThat(countPresetsByName(presetName))
            .as("a retry must NOT create a second preset")
            .isEqualTo(1);
    }

    @Test
    void create_replayAfterStartRightRevoked_answers403_notStale201() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UUID memberId = createUser("audit14rev" + suffix);
        String memberName = userRepository.findById(memberId).orElseThrow().getUsername();
        String memberToken = login(memberName, "passr");
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"userId\":\"" + memberId + "\",\"role\":\"DESIGNER\"}")
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        String key = UUID.randomUUID().toString();
        String body = createBody("revoke-" + suffix);

        MvcResult first = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + memberToken)
                        .header("Idempotency-Key", key)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        String storedBody = first.getResponse().getContentAsString();

        // Права сузились: участник больше не может создавать шаблоны.
        // (Гвард «последний OWNER» тут не срабатывает: участник — DESIGNER.)
        mockMvc.perform(delete("/processes/" + processKey + "/members/" + memberId)
                        .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        MvcResult replay = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + memberToken)
                        .header("Idempotency-Key", key)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden())
            .andReturn();

        assertThat(replay.getResponse().getContentAsString())
            .as("the stale stored success must not be served")
            .isNotEqualTo(storedBody);
    }

    // ==================== helpers ====================

    /** Сколько шаблонов с ТАКИМ именем — по всему списку (тесты класса делят процесс). */
    private long countPresetsByName(String name) throws Exception {
        MvcResult result = mockMvc.perform(get("/presets?key=" + processKey)
                        .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andReturn();
        long count = 0;
        for (JsonNode preset : mapper.readTree(result.getResponse().getContentAsString())) {
            if (name.equals(preset.get("name").asText())) {
                count++;
            }
        }
        return count;
    }

    private String createBody(String name) {
        return "{\"processDefinitionKey\":\"" + processKey + "\","
            + "\"targetKind\":\"START\","
            + "\"name\":\"" + name + "\","
            + "\"description\":\"audit14\","
            + "\"variables\":[{\"name\":\"a\",\"type\":\"STRING\",\"value\":\"1\"}]}";
    }

    private UUID createUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("passr"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
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
