package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CreatePresetDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.PresetHistoryEntryDTO;
import com.zorrodev.bpm.contract.dto.PresetImportDTO;
import com.zorrodev.bpm.contract.dto.PresetVisibilityDTO;
import com.zorrodev.bpm.contract.dto.UpdatePresetDTO;
import com.zorrodev.bpm.contract.dto.VariablePresetDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.AuditLogRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.VariablePresetFavoriteRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.VariablePresetService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-VT-1 критерии 2/3/4/5: CRUD сквозняком через HTTP, ACL-матрица
 * (все эндпоинты × {владелец, участник, админ процесса, viewer, не участник,
 * SUPER_ADMIN, аноним}), import/export round-trip, избранное, история,
 * видимость, аудит.
 *
 * <p>Роли (та же модель, что авторизация членства, P-24 — существующий
 * {@code AuthorizationService}, нового хелпера нет):
 * <ul>
 *   <li>{@code vt1owner} — DESIGNER: создавать (START) может, чужим управлять — нет;</li>
 *   <li>{@code vt1admin} — OWNER (админ процесса, НЕ владелец шаблонов): чужие
 *       видимые правит, чужие PRIVATE не видит (404);</li>
 *   <li>{@code vt1designer} — DESIGNER-участник: чужие PROCESS читает, править — 403;</li>
 *   <li>{@code vt1viewer} — VIEWER: чужие PROCESS читает, создавать — 403;</li>
 *   <li>{@code vt1outsider} — без членства: создавать — 403, чужое — 404/пусто;</li>
 *   <li>{@code admin} — SUPER_ADMIN: всё;</li>
 *   <li>без токена — 401.</li>
 * </ul>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresetResourceIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private VariablePresetFavoriteRepository favoriteRepository;
    @Autowired private VariablePresetService presetService;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String ownerToken;
    private String procAdminToken;
    private String designerToken;
    private String viewerToken;
    private String outsiderToken;
    private UUID ownerId;
    private String processKey;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        ownerId = createAndSaveUser("vt1owner");
        UUID procAdminId = createAndSaveUser("vt1procadmin");
        UUID designerId = createAndSaveUser("vt1designer");
        UUID viewerId = createAndSaveUser("vt1viewer");
        createAndSaveUser("vt1outsider");
        ownerToken = login("vt1owner", "passr");
        procAdminToken = login("vt1procadmin", "passn");
        designerToken = login("vt1designer", "passr");
        viewerToken = login("vt1viewer", "passr");
        outsiderToken = login("vt1outsider", "passr");

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
        ProcessEntity process = processRepository.findByDefinitionKey(processKey).orElseThrow();

        addMember(process.getId(), ownerId, "DESIGNER");
        addMember(process.getId(), procAdminId, "OWNER");
        addMember(process.getId(), designerId, "DESIGNER");
        addMember(process.getId(), viewerId, "VIEWER");
    }

    // ==================== критерий 2: CRUD сквозняком ====================

    @Test
    void crud_createReadUpdateDelete_roundTrip() throws Exception {
        VariablePresetDTO created = createPreset(ownerToken, processKey, "START", null,
            "crud-" + UUID.randomUUID(), "desc", vars(stringVar("a", "1")));
        assertThat(created.getId()).isNotNull();
        assertThat(created.getVersion()).isZero();
        assertThat(created.getTargetRef()).isNull();
        assertThat(created.getVisibility()).isEqualTo("PRIVATE");

        VariablePresetDTO read = getPreset(ownerToken, created.getId());
        assertThat(read.getName()).isEqualTo(created.getName());
        assertThat(triples(read.getVariables())).containsExactly(triple("a", "STRING", "1"));

        UpdatePresetDTO upd = new UpdatePresetDTO();
        upd.setDescription("new desc");
        upd.setVariables(List.of(stringVar("a", "2")));
        upd.setVersion(read.getVersion());
        VariablePresetDTO updated = updatePreset(ownerToken, created.getId(), upd);
        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(updated.getDescription()).isEqualTo("new desc");
        assertThat(triples(updated.getVariables())).containsExactly(triple("a", "STRING", "2"));

        // Stale-версия — 409 PRESET_CONFLICT.
        upd.setVersion(0);
        MvcResult conflict = mockMvc.perform(put("/presets/" + created.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(mapper.writeValueAsString(upd))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(codeOf(conflict)).isEqualTo("PRESET_CONFLICT");

        mockMvc.perform(delete("/presets/" + created.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/presets/" + created.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void crud_duplicateName_returns409() throws Exception {
        String name = "dup-" + UUID.randomUUID();
        createPreset(ownerToken, processKey, "START", null, name, null, vars(stringVar("a", "1")));
        MvcResult dup = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(presetJson(processKey, "START", null, name, null, vars(stringVar("a", "1")), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(codeOf(dup)).isEqualTo("PRESET_CONFLICT");
    }

    @Test
    void crud_unknownKindAndBadBinding_returns400_not500() throws Exception {
        MvcResult unknown = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(presetJson(processKey, "NOPE", null, "k-" + UUID.randomUUID(),
                            null, vars(stringVar("a", "1")), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(codeOf(unknown)).isEqualTo("PRESET_VALIDATION_FAILED");

        // targetRef обязателен для USER_TASK.
        MvcResult noRef = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(presetJson(processKey, "USER_TASK", null, "k-" + UUID.randomUUID(),
                            null, vars(stringVar("a", "1")), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(codeOf(noRef)).isEqualTo("PRESET_VALIDATION_FAILED");

        // Невалидное значение типа — 400 с перечнем полей.
        MvcResult badLong = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(presetJson(processKey, "START", null, "k-" + UUID.randomUUID(),
                            null, vars(longVar("n", "abc")), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(codeOf(badLong)).isEqualTo("PRESET_VALIDATION_FAILED");
        assertThat(fieldsOf(badLong)).anyMatch(f -> f.contains("value"));
    }

    // ==================== критерий 4: ACL-матрица ====================

    @Test
    void acl_create_whoMayCreate() throws Exception {
        // DESIGNER (START) и SUPER_ADMIN — могут; VIEWER/outsider — 403; аноним — 401.
        createPreset(designerToken, processKey, "START", null,
            "m-" + UUID.randomUUID(), null, vars(stringVar("a", "1")));
        createPreset(adminToken, processKey, "START", null,
            "m-" + UUID.randomUUID(), null, vars(stringVar("a", "1")));
        MvcResult viewer = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + viewerToken)
                        .content(presetJson(processKey, "START", null, "m-" + UUID.randomUUID(),
                            null, vars(stringVar("a", "1")), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(codeOf(viewer)).isEqualTo("PRESET_FORBIDDEN");
        MvcResult outsider = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .content(presetJson(processKey, "START", null, "m-" + UUID.randomUUID(),
                            null, vars(stringVar("a", "1")), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(codeOf(outsider)).isEqualTo("PRESET_FORBIDDEN");
        mockMvc.perform(post("/presets")
                        .content(presetJson(processKey, "START", null, "m-" + UUID.randomUUID(),
                            null, vars(stringVar("a", "1")), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acl_foreignPrivate_is404_everywhere() throws Exception {
        VariablePresetDTO foreign = createPreset(ownerToken, processKey, "START", null,
            "priv-" + UUID.randomUUID(), null, vars(stringVar("a", "1")));
        assertThat(foreign.getVisibility()).isEqualTo("PRIVATE");

        // Участник, админ процесса, outsider — 404 с кодом PRESET_NOT_FOUND (не 403).
        for (String token : List.of(designerToken, procAdminToken, outsiderToken)) {
            MvcResult r = mockMvc.perform(get("/presets/" + foreign.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andReturn();
            assertThat(codeOf(r)).isEqualTo("PRESET_NOT_FOUND");
        }
        // SUPER_ADMIN видит; аноним — 401.
        mockMvc.perform(get("/presets/" + foreign.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/presets/" + foreign.getId()))
                .andExpect(status().isUnauthorized());

        // Список чужого PRIVATE не содержит — ни у участника, ни у outsider.
        assertThat(listIds(designerToken, processKey)).doesNotContain(foreign.getId());
        assertThat(listIds(outsiderToken, processKey)).doesNotContain(foreign.getId());
        // …а владелец свой видит.
        assertThat(listIds(ownerToken, processKey)).contains(foreign.getId());
    }

    @Test
    void acl_processVisible_readableByMembers_notByOutsider() throws Exception {
        VariablePresetDTO shared = createPreset(ownerToken, processKey, "USER_TASK", "taskA",
            "shr-" + UUID.randomUUID(), null, vars(stringVar("a", "1")), "PROCESS");

        mockMvc.perform(get("/presets/" + shared.getId())
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/presets/" + shared.getId())
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        MvcResult outsider = mockMvc.perform(get("/presets/" + shared.getId())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(codeOf(outsider)).isEqualTo("PRESET_NOT_FOUND");
        assertThat(listIds(designerToken, processKey)).contains(shared.getId());
        assertThat(listIds(outsiderToken, processKey)).doesNotContain(shared.getId());
    }

    @Test
    void acl_edit_onlyOwnerOrProcessAdmin() throws Exception {
        VariablePresetDTO shared = createPreset(ownerToken, processKey, "START", null,
            "edt-" + UUID.randomUUID(), null, vars(stringVar("a", "1")), "PROCESS");

        // Админ процесса (не владелец) — правит чужой видимый.
        UpdatePresetDTO upd = new UpdatePresetDTO();
        upd.setDescription("by admin");
        upd.setVersion(0);
        mockMvc.perform(put("/presets/" + shared.getId())
                        .header("Authorization", "Bearer " + procAdminToken)
                        .content(mapper.writeValueAsString(upd))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        assertThat(getPreset(ownerToken, shared.getId()).getDescription()).isEqualTo("by admin");

        // Участник без MANAGE_MEMBERS — 403 PRESET_FORBIDDEN.
        UpdatePresetDTO upd2 = new UpdatePresetDTO();
        upd2.setDescription("by designer");
        upd2.setVersion(1);
        MvcResult forbidden = mockMvc.perform(put("/presets/" + shared.getId())
                        .header("Authorization", "Bearer " + designerToken)
                        .content(mapper.writeValueAsString(upd2))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(codeOf(forbidden)).isEqualTo("PRESET_FORBIDDEN");

        // Удаление чужого видимого участником — тоже 403; владельцем — ок.
        mockMvc.perform(delete("/presets/" + shared.getId())
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/presets/" + shared.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    // ==================== критерий 3: import/export round-trip ====================

    @Test
    void importExport_ownerExample_roundTripEquals() throws Exception {
        List<ProcessVariable> vars = List.of(
            longVar("createdEmployeeId", "12345"),
            uuidVar("sourceUuid", "123e4567-e89b-12d3-a456-426614174000"),
            jsonVar("stages", "{\"title\":\"Привет \\\"мир\\\"\",\"n\":2}"));
        PresetImportDTO in = new PresetImportDTO();
        in.setProcessDefinitionKey(processKey);
        in.setTargetKind("START");
        in.setName("imp-" + UUID.randomUUID());
        in.setVariables(new ArrayList<>(vars));

        MvcResult created = mockMvc.perform(post("/presets/import")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(mapper.writeValueAsString(in))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        UUID id = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        MvcResult exported = mockMvc.perform(get("/presets/" + id + "/export")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        PresetImportDTO out = mapper.readValue(
            exported.getResponse().getContentAsString(), PresetImportDTO.class);
        assertThat(out.getProcessDefinitionKey()).isEqualTo(processKey);
        assertThat(triples(out.getVariables())).containsExactly(
            triple("createdEmployeeId", "LONG", "12345"),
            triple("sourceUuid", "UUID", "123e4567-e89b-12d3-a456-426614174000"),
            triple("stages", "JSON", "{\"title\":\"Привет \\\"мир\\\"\",\"n\":2}"));
        assertThat(out.getVariables().get(2).getValue()).contains("Привет");
    }

    // ==================== п.6-бис: избранное ====================

    @Test
    void favorite_markUnmark_reflectedInReads_andCascadesOnDelete() throws Exception {
        VariablePresetDTO shared = createPreset(ownerToken, processKey, "START", null,
            "fav-" + UUID.randomUUID(), null, vars(stringVar("a", "1")), "PROCESS");

        assertThat(getPreset(designerToken, shared.getId()).isFavorite()).isFalse();
        mockMvc.perform(put("/presets/" + shared.getId() + "/favorite")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk());
        assertThat(getPreset(designerToken, shared.getId()).isFavorite()).isTrue();
        assertThat(getPreset(ownerToken, shared.getId()).isFavorite())
            .as("избранное — персонифицировано, у владельца пусто").isFalse();

        mockMvc.perform(delete("/presets/" + shared.getId() + "/favorite")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk());
        assertThat(getPreset(designerToken, shared.getId()).isFavorite()).isFalse();

        // Чужой PRIVATE отметить нельзя — 404.
        VariablePresetDTO priv = createPreset(ownerToken, processKey, "START", null,
            "favp-" + UUID.randomUUID(), null, vars(stringVar("a", "1")));
        mockMvc.perform(put("/presets/" + priv.getId() + "/favorite")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isNotFound());

        // Каскад: отметка + удаление шаблона = строка избранного исчезает.
        mockMvc.perform(put("/presets/" + shared.getId() + "/favorite")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/presets/" + shared.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        assertThat(favoriteRepository.findAll()).noneMatch(
            f -> f.getPresetId().equals(shared.getId()));
    }

    // ==================== п.6-бис: история ====================

    @Test
    void history_createAndUpdates_chainLinks_andNameOnlyAddsNothing() throws Exception {
        VariablePresetDTO created = createPreset(ownerToken, processKey, "START", null,
            "hist-" + UUID.randomUUID(), null, vars(stringVar("a", "1")));
        updateVariables(ownerToken, created.getId(), 0, vars(stringVar("a", "2")));
        updateVariables(ownerToken, created.getId(), 1, vars(stringVar("a", "3"), stringVar("b", "x")));
        // Смена только имени — запись в истории НЕ добавляет (снапшоты совпали бы).
        UpdatePresetDTO rename = new UpdatePresetDTO();
        rename.setName("hist-renamed-" + UUID.randomUUID());
        rename.setVersion(2);
        updatePreset(ownerToken, created.getId(), rename);

        MvcResult history = mockMvc.perform(get("/presets/" + created.getId() + "/history")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        List<PresetHistoryEntryDTO> entries = mapper.readValue(history.getResponse().getContentAsString(),
            mapper.getTypeFactory().constructCollectionType(List.class, PresetHistoryEntryDTO.class));
        assertThat(entries).hasSize(3);
        // Цепочка: CREATE(→A) → UPDATE(A→B) → UPDATE(B→C).
        PresetHistoryEntryDTO create = entries.get(0);
        assertThat(create.getAction()).isEqualTo("CREATE");
        assertThat(create.getVariablesBefore()).isNull();
        assertThat(triples(create.getVariablesAfter())).containsExactly(triple("a", "STRING", "1"));
        assertThat(create.getActorUserId()).isEqualTo(ownerId);

        PresetHistoryEntryDTO first = entries.get(1);
        assertThat(first.getAction()).isEqualTo("UPDATE");
        assertThat(triples(first.getVariablesBefore())).containsExactly(triple("a", "STRING", "1"));
        assertThat(triples(first.getVariablesAfter())).containsExactly(triple("a", "STRING", "2"));

        PresetHistoryEntryDTO second = entries.get(2);
        assertThat(triples(second.getVariablesBefore())).containsExactly(triple("a", "STRING", "2"));
        assertThat(triples(second.getVariablesAfter())).containsExactly(
            triple("a", "STRING", "3"), triple("b", "STRING", "x"));

        // Участник PROCESS-шаблона историю видит; чужой PRIVATE — 404.
        VariablePresetDTO shared = createPreset(ownerToken, processKey, "START", null,
            "histshr-" + UUID.randomUUID(), null, vars(stringVar("a", "1")), "PROCESS");
        mockMvc.perform(get("/presets/" + shared.getId() + "/history")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk());
        VariablePresetDTO priv = createPreset(ownerToken, processKey, "START", null,
            "histpriv-" + UUID.randomUUID(), null, vars(stringVar("a", "1")));
        mockMvc.perform(get("/presets/" + priv.getId() + "/history")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isNotFound());
    }

    // ==================== п.6-бис: видимость ====================

    @Test
    void visibility_privateToProcess_opensReads_andAudited() throws Exception {
        VariablePresetDTO priv = createPreset(ownerToken, processKey, "START", null,
            "vis-" + UUID.randomUUID(), null, vars(stringVar("a", "1")));
        mockMvc.perform(get("/presets/" + priv.getId())
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isNotFound());

        PresetVisibilityDTO body = new PresetVisibilityDTO();
        body.setVisibility("PROCESS");
        MvcResult changed = mockMvc.perform(put("/presets/" + priv.getId() + "/visibility")
                        .header("Authorization", "Bearer " + ownerToken)
                        .content(mapper.writeValueAsString(body))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(changed.getResponse().getContentAsString())
            .get("visibility").asText()).isEqualTo("PROCESS");
        mockMvc.perform(get("/presets/" + priv.getId())
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isOk());

        // Участник без прав — 403; версия бампнулась (конкурентный PUT со stale — 409).
        MvcResult forbidden = mockMvc.perform(put("/presets/" + priv.getId() + "/visibility")
                        .header("Authorization", "Bearer " + designerToken)
                        .content(mapper.writeValueAsString(body))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(codeOf(forbidden)).isEqualTo("PRESET_FORBIDDEN");

        assertThat(auditLogRepository.findByFilters(processKey, ownerId, null, null)).anyMatch(
            e -> "PRESET_VISIBILITY".equals(e.getAction())
                && priv.getId().toString().equals(e.getTargetId()));
    }

    // ==================== критерий 5: аудит ====================

    @Test
    void audit_createUpdateDelete_recorded() throws Exception {
        VariablePresetDTO created = createPreset(ownerToken, processKey, "START", null,
            "aud-" + UUID.randomUUID(), null, vars(stringVar("a", "1")));
        updateVariables(ownerToken, created.getId(), 0, vars(stringVar("a", "2")));
        mockMvc.perform(delete("/presets/" + created.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        List<AuditLogEntity> rows = auditLogRepository.findByFilters(processKey, ownerId, null, null);
        assertThat(rows).filteredOn(e -> created.getId().toString().equals(e.getTargetId()))
            .extracting(AuditLogEntity::getAction)
            .containsExactlyInAnyOrder("PRESET_CREATE", "PRESET_UPDATE", "PRESET_DELETE");
    }

    @Test
    void limit_200_perKeyOwner_201stRejected() {
        UUID limitUser = userRepository.findByUsername("vt1limit").map(UiUserEntity::getId).orElse(null);
        assertThat(limitUser).as("каждый прогон — свой пользователь лимита").isNull();
        UUID userId = createAndSaveUser("vt1limit");
        ProcessEntity process = processRepository.findByDefinitionKey(processKey).orElseThrow();
        addMember(process.getId(), userId, "DESIGNER");
        Principal principal = new Principal.UserPrincipal(userId, "vt1limit", "USER");

        for (int i = 0; i < 200; i++) {
            presetService.create(principal, new VariablePresetService.PresetPayload(
                processKey, com.zorrodev.bpm.engine.entity.VariablePresetTargetKind.START,
                null, "lim-" + i, null, "[{\"name\":\"a\",\"type\":\"STRING\",\"value\":\"1\"}]",
                com.zorrodev.bpm.engine.entity.VariablePresetVisibility.PRIVATE));
        }
        assertThatThrownBy(() -> presetService.create(principal,
            new VariablePresetService.PresetPayload(processKey,
                com.zorrodev.bpm.engine.entity.VariablePresetTargetKind.START,
                null, "lim-overflow", null, "[{\"name\":\"a\",\"type\":\"STRING\",\"value\":\"1\"}]",
                com.zorrodev.bpm.engine.entity.VariablePresetVisibility.PRIVATE)))
            .as("201-й шаблон — PRESET_LIMIT_EXCEEDED")
            .matches(e -> e instanceof com.zorrodev.bpm.contract.exception.ApiException api
                && "PRESET_LIMIT_EXCEEDED".equals(api.getCode()));
    }

    // ==================== helpers ====================

    private VariablePresetDTO createPreset(String token, String key, String kind, String ref,
            String name, String desc, List<ProcessVariable> variables) throws Exception {
        return createPreset(token, key, kind, ref, name, desc, variables, null);
    }

    private VariablePresetDTO createPreset(String token, String key, String kind, String ref,
            String name, String desc, List<ProcessVariable> variables, String visibility) throws Exception {
        MvcResult result = mockMvc.perform(post("/presets")
                        .header("Authorization", "Bearer " + token)
                        .content(presetJson(key, kind, ref, name, desc, variables, visibility))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), VariablePresetDTO.class);
    }

    private String presetJson(String key, String kind, String ref, String name, String desc,
            List<ProcessVariable> variables, String visibility) throws Exception {
        CreatePresetDTO dto = new CreatePresetDTO();
        dto.setProcessDefinitionKey(key);
        dto.setTargetKind(kind);
        dto.setTargetRef(ref);
        dto.setName(name);
        dto.setDescription(desc);
        dto.setVariables(new ArrayList<>(variables));
        dto.setVisibility(visibility);
        return mapper.writeValueAsString(dto);
    }

    private VariablePresetDTO getPreset(String token, UUID id) throws Exception {
        MvcResult result = mockMvc.perform(get("/presets/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), VariablePresetDTO.class);
    }

    private VariablePresetDTO updatePreset(String token, UUID id, UpdatePresetDTO dto) throws Exception {
        MvcResult result = mockMvc.perform(put("/presets/" + id)
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), VariablePresetDTO.class);
    }

    private void updateVariables(String token, UUID id, int version, List<ProcessVariable> variables)
            throws Exception {
        UpdatePresetDTO dto = new UpdatePresetDTO();
        dto.setVariables(new ArrayList<>(variables));
        dto.setVersion(version);
        updatePreset(token, id, dto);
    }

    private List<UUID> listIds(String token, String key) throws Exception {
        MvcResult result = mockMvc.perform(get("/presets?key=" + key)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        List<VariablePresetDTO> list = mapper.readValue(result.getResponse().getContentAsString(),
            mapper.getTypeFactory().constructCollectionType(List.class, VariablePresetDTO.class));
        return list.stream().map(VariablePresetDTO::getId).toList();
    }

    private String codeOf(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get("code").asText();
    }

    private List<String> fieldsOf(MvcResult result) throws Exception {
        List<Map<String, String>> fields = mapper.readValue(
            mapper.readTree(result.getResponse().getContentAsString()).get("params").get("fields").toString(),
            mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        return fields.stream().map(f -> f.get("field")).toList();
    }

    private static List<ProcessVariable> vars(ProcessVariable... vs) {
        return new ArrayList<>(List.of(vs));
    }

    private static ProcessVariable stringVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private static ProcessVariable longVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.LONG);
        v.setValue(value);
        return v;
    }

    private static ProcessVariable uuidVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.UUID);
        v.setValue(value);
        return v;
    }

    private static ProcessVariable jsonVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.JSON);
        v.setValue(value);
        return v;
    }

    private static String triple(String name, String type, String value) {
        return name + "|" + type + "|" + value;
    }

    private static List<String> triples(List<ProcessVariable> vars) {
        return vars.stream().map(v -> triple(v.getName(), v.getType().name(), v.getValue())).toList();
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

    private UUID createAndSaveUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass" + username.charAt(username.length() - 1)));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private void addMember(UUID processId, UUID userId, String role) {
        ProcessMemberId id = new ProcessMemberId(processId, userId);
        if (processMemberRepository.existsById(id)) {
            return;
        }
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(processId);
        pm.setUserId(userId);
        pm.setRole(role);
        pm.setAddedBy(userId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
    }
}
