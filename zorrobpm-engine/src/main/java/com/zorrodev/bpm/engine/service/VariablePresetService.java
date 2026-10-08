package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.CreatePresetDTO;
import com.zorrodev.bpm.contract.dto.PresetHistoryEntryDTO;
import com.zorrodev.bpm.contract.dto.PresetImportDTO;
import com.zorrodev.bpm.contract.dto.UpdatePresetDTO;
import com.zorrodev.bpm.contract.dto.VariablePresetDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.VariablePresetEntity;
import com.zorrodev.bpm.engine.entity.VariablePresetHistoryEntity;
import com.zorrodev.bpm.engine.entity.VariablePresetFavoriteEntity;
import com.zorrodev.bpm.engine.entity.VariablePresetTargetKind;
import com.zorrodev.bpm.engine.entity.VariablePresetVisibility;
import com.zorrodev.bpm.engine.repository.VariablePresetFavoriteRepository;
import com.zorrodev.bpm.engine.repository.VariablePresetHistoryRepository;
import com.zorrodev.bpm.engine.repository.VariablePresetRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * WO-VT-1: CRUD шаблонов переменных с авторизацией, лимитами и аудитом.
 *
 * <p>Модель доступа (default DENY, G-L):
 * <ul>
 *   <li>401 PRESET_UNAUTHORIZED — без принципала;</li>
 *   <li>читать — владелец или (для PROCESS) участник процесса с VIEW_MEMBERS
 *       (есть у любой роли, включая VIEWER — тот же хелпер, что авторизация
 *       членства, P-24); чужой PRIVATE — 404 PRESET_NOT_FOUND, а не 403, чтобы
 *       не раскрывать существование;</li>
 *   <li>создавать — участник с правом запуска (START; OWNER/DESIGNER), иначе
 *       403 PRESET_FORBIDDEN; SUPER_ADMIN — всё;</li>
 *   <li>менять/удалять — владелец, админ процесса (MANAGE_MEMBERS) или
 *       SUPER_ADMIN; видимый, но чужой — 403 PRESET_FORBIDDEN.</li>
 * </ul>
 *
 * <p>Привязка {@code (processDefinitionKey, targetKind, targetRef)} после создания
 * immutable (смена = новый шаблон) — так UNIQUE {@code (owner, привязка, name)} не
 * расползается через PUT. Пустая строка вместо NULL в {@code targetRef} — см.
 * комментарий changeset 20261007-120 (H2/PG-паритет индекса без expression-формы).
 */
@Service
@RequiredArgsConstructor
public class VariablePresetService {

    private final VariablePresetRepository presetRepository;
    private final VariablePresetFavoriteRepository favoriteRepository;
    private final VariablePresetHistoryRepository historyRepository;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final UiUserRepository userRepository;

/* WO-VT-1: граница WO-DEBT-7 — ресурс в zorrobpm-rest НЕ импортирует
 * engine.entity/* (ловит RestRepositoryBoundaryTest), поэтому сервис принимает
 * contract-DTO со строками (как ProcessMemberService принимает AddMemberDTO)
 * и отдаёт готовые DTO: весь entity-маппинг — здесь, внутри engine.
 */

    /**
     * Создание — строковые kind/visibility (неизвестные — машинная
     * PRESET_VALIDATION_FAILED, а не 500); variables — готовый список.
     */
    public record PresetPayload(
        String processDefinitionKey,
        String targetKind,
        String targetRef,
        String name,
        String description,
        List<ProcessVariable> variables,
        String visibility) {
    }

    @Transactional(readOnly = true)
    public VariablePresetDTO get(Principal principal, UUID id) {
        requireAuth(principal);
        VariablePresetEntity entity = presetRepository.findById(id)
            .orElseThrow(() -> notFound());
        if (!canRead(principal, entity)) {
            // Чужой PRIVATE — как отсутствующий (не раскрываем существование).
            throw notFound();
        }
        return toDto(entity, isFavoriteOf(entity.getId(), principal));
    }

    @Transactional(readOnly = true)
    public List<VariablePresetDTO> list(Principal principal, String key, String kind, String ref) {
        requireAuth(principal);
        VariablePresetTargetKind targetKind = parseKind(kind);
        UUID me = actorUserId(principal);
        // Свои — все, фильтр matches() ниже (по key/kind/ref при наличии).
        List<VariablePresetEntity> own = presetRepository.findByOwnerUserId(me);
        List<VariablePresetEntity> shared = new ArrayList<>();
        if (key != null && !key.isBlank()
            && authorizationService.canOperate(
                principal, key, AuthorizationService.Action.VIEW_MEMBERS)) {
            // Чужие PROCESS только там, где вызывающий — участник. Без canOperate —
            // пусто (не раскрываем даже факта наличия чужих шаблонов).
            if (targetKind != null) {
                shared.addAll(presetRepository.findSharedAtBinding(
                    VariablePresetVisibility.PROCESS, key, targetKind,
                    normalizeStoredRef(targetKind, ref), me));
            } else {
                shared.addAll(presetRepository.findSharedInKeys(
                    VariablePresetVisibility.PROCESS, List.of(key), me));
            }
        }
        List<VariablePresetDTO> out = new ArrayList<>(own.size() + shared.size());
        for (VariablePresetEntity e : own) {
            if (matches(e, key, targetKind, ref)) {
                out.add(toDto(e, isFavoriteOf(e.getId(), principal)));
            }
        }
        for (VariablePresetEntity e : shared) {
            if (matches(e, key, targetKind, ref)
                    && out.stream().noneMatch(x -> x.getId().equals(e.getId()))) {
                out.add(toDto(e, isFavoriteOf(e.getId(), principal)));
            }
        }
        return out;
    }

    @Transactional
    public VariablePresetDTO create(Principal principal, PresetPayload payload) {
        requireAuth(principal);
        requireCanLaunch(principal, payload.processDefinitionKey());
        requireVariables(payload.variables());
        VariablePresetTargetKind targetKind = parseKind(payload.targetKind());
        VariablePresetVisibility visibility = parseVisibilityOrDefault(payload.visibility());
        List<VariablePresetValidator.FieldError> errors = new ArrayList<>();
        errors.addAll(VariablePresetValidator.validateName(payload.name()));
        errors.addAll(VariablePresetValidator.validateBinding(
            payload.processDefinitionKey(), targetKind, payload.targetRef()));
        VariablePresetValidator.VariablesResult vars =
            VariablePresetValidator.parseAndValidate(
                VariablePresetValidator.toJson(payload.variables()));
        errors.addAll(vars.errors());
        failOnErrors(errors);
        UUID owner = actorUserId(principal);
        // WO-VT-1 раунд 2 (Б-2): лимит ≤200 на (ключ, владелец) проверяется
        // count-pre-check — под гонкой два создания видят один счёт и оба
        // проходят (204>200 живьём у красной команды, тот же TOCTOU-класс, что
        // Б-1). Сериализуем создания владельца построчной блокировкой его же
        // строки ui_users в той же транзакции (санкция CTO на этот WO; shape —
        // UiUserRepository.findByIdForUpdate, WO-REL-39). Строка владельца есть
        // на всех достижимых путях (аутентифицированный пользователь; владелец
        // живого ключа); блокировка берётся ДО count — иначе она декоративна.
        userRepository.findByIdForUpdate(owner);
        if (presetRepository.countByOwnerUserIdAndProcessDefinitionKey(
                owner, payload.processDefinitionKey()) >= VariablePresetValidator.MAX_PRESETS_PER_KEY_OWNER) {
            throw new ApiException(HttpStatus.CONFLICT, "PRESET_LIMIT_EXCEEDED",
                "Too many presets for this process (max "
                    + VariablePresetValidator.MAX_PRESETS_PER_KEY_OWNER + ")",
                Map.of("processDefinitionKey", payload.processDefinitionKey()));
        }
        String storedRef = normalizeStoredRef(targetKind, payload.targetRef());
        if (presetRepository.existsByOwnerUserIdAndProcessDefinitionKeyAndTargetKindAndTargetRefAndName(
                owner, payload.processDefinitionKey(), targetKind, storedRef, payload.name().trim())) {
            throw conflict("A preset with this name already exists here");
        }
        VariablePresetEntity entity = new VariablePresetEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessDefinitionKey(payload.processDefinitionKey());
        entity.setTargetKind(targetKind);
        entity.setTargetRef(storedRef);
        entity.setName(payload.name().trim());
        entity.setDescription(blankToNull(payload.description()));
        entity.setVariables(VariablePresetValidator.toJson(vars.variables()));
        entity.setOwnerUserId(owner);
        entity.setVisibility(visibility);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        entity.setVersion(0);
        try {
            entity = presetRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // Гонка двух созданий с тем же именем — UNIQUE-индекс решает, не pre-check.
            throw conflict("A preset with this name already exists here");
        }
        auditLogService.record(principal, "PRESET_CREATE",
            entity.getProcessDefinitionKey(), entity.getId().toString());
        recordHistory(principal, entity.getId(), "CREATE", null, entity.getVariables());
        return toDto(entity, false);
    }

    @Transactional
    public VariablePresetDTO update(Principal principal, UUID id, UpdatePresetDTO dto) {
        requireAuth(principal);
        // WO-VT-1 раунд 2 (Б-1): чтение под запись (SELECT ... FOR UPDATE) —
        // два update с одной версией сериализуются: проигравший ждёт коммита
        // победителя, видит свежую версию и уходит в 409 ниже, а не затирает
        // чужую правку. Обычный findById здесь давал 200/200 с потерей (20/20).
        VariablePresetEntity entity = presetRepository.findByIdForUpdate(id)
            .orElseThrow(VariablePresetService::notFound);
        if (!canRead(principal, entity)) {
            throw notFound();
        }
        if (!canEdit(principal, entity)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PRESET_FORBIDDEN",
                "Only the owner, a process admin or SUPER_ADMIN may change this preset",
                Map.of("id", id.toString()));
        }
        if (entity.getVersion() != dto.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "PRESET_CONFLICT",
                "Preset was changed concurrently (expected version " + dto.getVersion()
                    + ", actual " + entity.getVersion() + ")",
                Map.of("expectedVersion", dto.getVersion(), "actualVersion", entity.getVersion()));
        }
        List<VariablePresetValidator.FieldError> errors = new ArrayList<>();
        String newName = entity.getName();
        if (dto.getName() != null) {
            errors.addAll(VariablePresetValidator.validateName(dto.getName()));
            newName = dto.getName().trim();
        }
        List<ProcessVariable> newVars = null;
        if (dto.getVariables() != null) {
            requireVariables(dto.getVariables());
            VariablePresetValidator.VariablesResult vars =
                VariablePresetValidator.parseAndValidate(
                    VariablePresetValidator.toJson(dto.getVariables()));
            errors.addAll(vars.errors());
            newVars = vars.variables();
        }
        VariablePresetVisibility newVisibility = entity.getVisibility();
        if (dto.getVisibility() != null) {
            newVisibility = parseVisibility(dto.getVisibility());
        }
        failOnErrors(errors);
        if (!newName.equals(entity.getName())
            && presetRepository.existsByOwnerUserIdAndProcessDefinitionKeyAndTargetKindAndTargetRefAndName(
                entity.getOwnerUserId(), entity.getProcessDefinitionKey(),
                entity.getTargetKind(), entity.getTargetRef(), newName)) {
            throw conflict("A preset with this name already exists here");
        }
        entity.setName(newName);
        if (dto.getDescription() != null) {
            entity.setDescription(blankToNull(dto.getDescription()));
        }
        String variablesBefore = null;
        if (newVars != null) {
            variablesBefore = entity.getVariables();
            entity.setVariables(VariablePresetValidator.toJson(newVars));
        }
        entity.setVisibility(newVisibility);
        entity.setUpdatedAt(Instant.now());
        entity.setVersion(entity.getVersion() + 1);
        try {
            entity = presetRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw conflict("A preset with this name already exists here");
        }
        auditLogService.record(principal, "PRESET_UPDATE",
            entity.getProcessDefinitionKey(), entity.getId().toString());
        if (variablesBefore != null) {
            // История — только когда менялись переменные (смена имени/описания
            // фиксируется аудитом, снапшоты до/после совпали бы побайтово).
            recordHistory(principal, entity.getId(), "UPDATE", variablesBefore, entity.getVariables());
        }
        return toDto(entity, isFavoriteOf(entity.getId(), principal));
    }

    @Transactional
    public void delete(Principal principal, UUID id) {
        requireAuth(principal);
        VariablePresetEntity entity = presetRepository.findById(id)
            .orElseThrow(VariablePresetService::notFound);
        if (!canRead(principal, entity)) {
            throw notFound();
        }
        if (!canEdit(principal, entity)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PRESET_FORBIDDEN",
                "Only the owner, a process admin or SUPER_ADMIN may delete this preset",
                Map.of("id", id.toString()));
        }
        presetRepository.delete(entity);
        auditLogService.record(principal, "PRESET_DELETE",
            entity.getProcessDefinitionKey(), id.toString());
        // DELETE в history-таблицу не пишется сознательно: строки истории каскадно
        // умирают вместе с шаблоном (ON DELETE CASCADE), факт удаления — в аудите
        // выше; эндпоинт истории удалённого шаблона всё равно 404 (§5 WO).
    }

    /**
     * WO-VT-1 п.6-бис: смена видимости PRIVATE↔PROCESS без версии (в отличие от
     * PUT): владелец, админ процесса или SUPER_ADMIN; видимый чужой — 403.
     * Версия бампится, чтобы конкурентный PUT со stale-версией дал 409, а не
     * молча потерял смену видимости.
     */
    @Transactional
    public VariablePresetDTO changeVisibility(Principal principal, UUID id, String visibility) {
        requireAuth(principal);
        // WO-VT-1 раунд 2 (Б-1, та же причина, что в update): видимость тоже
        // бампит версию — чтение под запись, иначе конкурентный PUT со
        // stale-версией молча теряет смену видимости вместо 409.
        VariablePresetEntity entity = presetRepository.findByIdForUpdate(id)
            .orElseThrow(VariablePresetService::notFound);
        if (!canRead(principal, entity)) {
            throw notFound();
        }
        if (!canEdit(principal, entity)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PRESET_FORBIDDEN",
                "Only the owner, a process admin or SUPER_ADMIN may change visibility",
                Map.of("id", id.toString()));
        }
        if (visibility == null || visibility.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRESET_VALIDATION_FAILED",
                "Preset validation failed",
                Map.of("fields", List.of(Map.of("field", "visibility", "message", "visibility is required"))));
        }
        entity.setVisibility(parseVisibility(visibility));
        entity.setUpdatedAt(Instant.now());
        entity.setVersion(entity.getVersion() + 1);
        entity = presetRepository.saveAndFlush(entity);
        auditLogService.record(principal, "PRESET_VISIBILITY",
            entity.getProcessDefinitionKey(), entity.getId().toString());
        return toDto(entity, isFavoriteOf(entity.getId(), principal));
    }

    /**
     * WO-VT-1 п.6-бис: избранное. Отметить может любой, кто видит шаблон
     * (canRead — чужой PRIVATE здесь тоже 404). Идемпотентно в обе стороны;
     * аудит не пишется (критерий 5 WO — только create/update/delete).
     *
     * @return текущее состояние отметки после операции
     */
    @Transactional
    public boolean setFavorite(Principal principal, UUID id, boolean favorite) {
        requireAuth(principal);
        VariablePresetEntity entity = presetRepository.findById(id)
            .orElseThrow(VariablePresetService::notFound);
        if (!canRead(principal, entity)) {
            throw notFound();
        }
        UUID me = actorUserId(principal);
        if (favorite) {
            if (!favoriteRepository.existsByUserIdAndPresetId(me, id)) {
                VariablePresetFavoriteEntity row = new VariablePresetFavoriteEntity();
                row.setUserId(me);
                row.setPresetId(id);
                row.setCreatedAt(Instant.now());
                try {
                    favoriteRepository.saveAndFlush(row);
                } catch (DataIntegrityViolationException e) {
                    // Гонка двух отметок — PK решает, не pre-check.
                }
            }
            return true;
        }
        favoriteRepository.deleteByUserIdAndPresetId(me, id);
        return false;
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(Principal principal, UUID id) {
        requireAuth(principal);
        VariablePresetEntity entity = presetRepository.findById(id)
            .orElseThrow(VariablePresetService::notFound);
        if (!canRead(principal, entity)) {
            throw notFound();
        }
        return favoriteRepository.existsByUserIdAndPresetId(actorUserId(principal), id);
    }

    /**
     * WO-VT-1 п.6-бис: история правок. Права — как на чтение (владелец / админ /
     * участник для PROCESS; чужой PRIVATE — 404). Возвращает CREATE/UPDATE со
     * снапшотами; DELETE — только в аудит-логе (см. комментарий в delete()).
     */
    @Transactional(readOnly = true)
    public List<PresetHistoryEntryDTO> history(Principal principal, UUID id) {
        requireAuth(principal);
        VariablePresetEntity entity = presetRepository.findById(id)
            .orElseThrow(VariablePresetService::notFound);
        if (!canRead(principal, entity)) {
            throw notFound();
        }
        return historyRepository.findByPresetIdOrderByAtAscIdAsc(id).stream().map(r -> {
            PresetHistoryEntryDTO dto = new PresetHistoryEntryDTO();
            dto.setId(r.getId());
            dto.setAction(r.getAction());
            dto.setActorUserId(r.getActorUserId());
            dto.setAt(r.getAt());
            dto.setVariablesBefore(r.getVariablesBefore() == null
                ? null : VariablePresetValidator.parseStored(r.getVariablesBefore()));
            dto.setVariablesAfter(r.getVariablesAfter() == null
                ? null : VariablePresetValidator.parseStored(r.getVariablesAfter()));
            return dto;
        }).toList();
    }

    /**
     * WO-VT-1 п.3: export — то же тело, что import (формат запроса старта +
     * привязка и имя). Права — как на чтение.
     */
    @Transactional(readOnly = true)
    public PresetImportDTO exportPreset(Principal principal, UUID id) {
        requireAuth(principal);
        VariablePresetEntity entity = presetRepository.findById(id)
            .orElseThrow(VariablePresetService::notFound);
        if (!canRead(principal, entity)) {
            throw notFound();
        }
        PresetImportDTO dto = new PresetImportDTO();
        dto.setProcessDefinitionKey(entity.getProcessDefinitionKey());
        dto.setTargetKind(entity.getTargetKind().name());
        dto.setTargetRef(storedRefToApi(entity.getTargetRef()));
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setVisibility(entity.getVisibility().name());
        dto.setVariables(VariablePresetValidator.parseStored(entity.getVariables()));
        return dto;
    }

    /** Import — тот же create, другим именем (тело как в запросе старта, п.3 WO). */
    @Transactional
    public VariablePresetDTO importPreset(Principal principal, PresetImportDTO dto) {
        return create(principal, new PresetPayload(dto.getProcessDefinitionKey(),
            dto.getTargetKind(), dto.getTargetRef(), dto.getName(), dto.getDescription(),
            dto.getVariables(), dto.getVisibility()));
    }

    @Transactional
    public VariablePresetDTO createFromContract(Principal principal, CreatePresetDTO dto) {
        return create(principal, new PresetPayload(dto.getProcessDefinitionKey(),
            dto.getTargetKind(), dto.getTargetRef(), dto.getName(), dto.getDescription(),
            dto.getVariables(), dto.getVisibility()));
    }

    private void recordHistory(Principal principal, UUID presetId, String action,
            String beforeJson, String afterJson) {
        VariablePresetHistoryEntity row = new VariablePresetHistoryEntity();
        row.setId(UUID.randomUUID());
        row.setPresetId(presetId);
        row.setAction(action);
        row.setActorUserId(actorUserId(principal));
        row.setAt(Instant.now());
        row.setVariablesBefore(beforeJson);
        row.setVariablesAfter(afterJson);
        historyRepository.save(row);
    }

    private void requireAuth(Principal principal) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PRESET_UNAUTHORIZED",
                "Authentication required", Map.of());
        }
    }

    private void requireCanLaunch(Principal principal, String processDefinitionKey) {
        if (!authorizationService.canOperate(
                principal, processDefinitionKey, AuthorizationService.Action.START)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PRESET_FORBIDDEN",
                "Only a process participant with launch rights may create presets",
                Map.of());
        }
    }

    private boolean canRead(Principal principal, VariablePresetEntity entity) {
        if (principal.isSuperAdmin()) return true;
        UUID me = actorUserId(principal);
        if (me != null && me.equals(entity.getOwnerUserId())) return true;
        return entity.getVisibility() == VariablePresetVisibility.PROCESS
            && authorizationService.canOperate(
                principal, entity.getProcessDefinitionKey(), AuthorizationService.Action.VIEW_MEMBERS);
    }

    private boolean canEdit(Principal principal, VariablePresetEntity entity) {
        if (principal.isSuperAdmin()) return true;
        UUID me = actorUserId(principal);
        if (me != null && me.equals(entity.getOwnerUserId())) return true;
        // Админ процесса (управляет участниками) — не любой участник.
        return authorizationService.canOperate(
            principal, entity.getProcessDefinitionKey(), AuthorizationService.Action.MANAGE_MEMBERS);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PRESET_NOT_FOUND",
            "Preset not found", Map.of());
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "PRESET_CONFLICT", message, Map.of());
    }

    private static void failOnErrors(List<VariablePresetValidator.FieldError> errors) {
        if (!errors.isEmpty()) {
            List<Map<String, String>> fields = errors.stream()
                .map(e -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("field", e.field());
                    m.put("message", e.message());
                    return m;
                })
                .toList();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("fields", fields);
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRESET_VALIDATION_FAILED",
                "Preset validation failed", params);
        }
    }

    private static UUID actorUserId(Principal principal) {
        return switch (principal) {
            case Principal.UserPrincipal u -> u.userId();
            case Principal.ServicePrincipal sa -> sa.ownerUserId();
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Хранение '' → API null (см. комментарий changeset 20261007-120). */
    private static String storedRefToApi(String stored) {
        return stored == null || stored.isEmpty() ? null : stored;
    }

    private static void requireVariables(List<ProcessVariable> variables) {
        if (variables == null || variables.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRESET_VALIDATION_FAILED",
                "Preset validation failed",
                Map.of("fields", List.of(
                    Map.of("field", "variables", "message", "variables must be a non-empty array"))));
        }
    }

    /** Неизвестный kind — машинная PRESET_VALIDATION_FAILED, а не 500. */
    static VariablePresetTargetKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        try {
            return VariablePresetTargetKind.valueOf(kind.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRESET_VALIDATION_FAILED",
                "Preset validation failed",
                Map.of("fields", List.of(
                    Map.of("field", "targetKind", "message", "unknown targetKind '" + kind + "'"))));
        }
    }

    static VariablePresetVisibility parseVisibility(String visibility) {
        try {
            return VariablePresetVisibility.valueOf(visibility.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRESET_VALIDATION_FAILED",
                "Preset validation failed",
                Map.of("fields", List.of(
                    Map.of("field", "visibility", "message", "unknown visibility '" + visibility + "'"))));
        }
    }

    private static VariablePresetVisibility parseVisibilityOrDefault(String visibility) {
        if (visibility == null || visibility.isBlank()) return VariablePresetVisibility.PRIVATE;
        return parseVisibility(visibility);
    }

    private VariablePresetDTO toDto(VariablePresetEntity e, boolean favorite) {
        VariablePresetDTO dto = new VariablePresetDTO();
        dto.setId(e.getId());
        dto.setProcessDefinitionKey(e.getProcessDefinitionKey());
        dto.setTargetKind(e.getTargetKind().name());
        dto.setTargetRef(storedRefToApi(e.getTargetRef()));
        dto.setName(e.getName());
        dto.setDescription(e.getDescription());
        dto.setVariables(VariablePresetValidator.parseStored(e.getVariables()));
        dto.setOwnerUserId(e.getOwnerUserId());
        dto.setVisibility(e.getVisibility().name());
        dto.setFavorite(favorite);
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setVersion(e.getVersion());
        return dto;
    }

    private boolean isFavoriteOf(UUID presetId, Principal principal) {
        return favoriteRepository.existsByUserIdAndPresetId(actorUserId(principal), presetId);
    }

    /**
     * Нормализация ref для хранения: START → '' (пусто; см. changeset-комментарий),
     * остальные — trim. Чтение отдаёт '' обратно как null (в маппинге ресурса).
     */
    static String normalizeStoredRef(VariablePresetTargetKind kind, String ref) {
        if (kind == VariablePresetTargetKind.START) return "";
        return ref == null ? "" : ref.trim();
    }

    private static boolean matches(VariablePresetEntity e, String key,
            VariablePresetTargetKind kind, String ref) {
        if (key != null && !key.isBlank() && !key.equals(e.getProcessDefinitionKey())) return false;
        if (kind != null && kind != e.getTargetKind()) return false;
        if (ref != null && !ref.isBlank()) {
            String stored = e.getTargetRef() == null || e.getTargetRef().isEmpty()
                ? null : e.getTargetRef();
            if (!ref.trim().equals(stored)) return false;
        }
        return true;
    }
}
