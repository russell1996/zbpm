package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.VariablePresetEntity;
import com.zorrodev.bpm.engine.entity.VariablePresetTargetKind;
import com.zorrodev.bpm.engine.entity.VariablePresetVisibility;
import com.zorrodev.bpm.engine.repository.VariablePresetRepository;
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
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;

    public record PresetPayload(
        String processDefinitionKey,
        VariablePresetTargetKind targetKind,
        String targetRef,
        String name,
        String description,
        String variablesJson,
        VariablePresetVisibility visibility) {
    }

    @Transactional(readOnly = true)
    public VariablePresetEntity get(Principal principal, UUID id) {
        requireAuth(principal);
        VariablePresetEntity entity = presetRepository.findById(id)
            .orElseThrow(() -> notFound());
        if (!canRead(principal, entity)) {
            // Чужой PRIVATE — как отсутствующий (не раскрываем существование).
            throw notFound();
        }
        return entity;
    }

    @Transactional(readOnly = true)
    public List<VariablePresetEntity> list(Principal principal, String key,
            VariablePresetTargetKind kind, String ref) {
        requireAuth(principal);
        UUID me = actorUserId(principal);
        // Свои — все, фильтр matches() ниже (по key/kind/ref при наличии).
        List<VariablePresetEntity> own = presetRepository.findByOwnerUserId(me);
        List<VariablePresetEntity> shared = new ArrayList<>();
        if (key != null && !key.isBlank()
            && authorizationService.canOperate(
                principal, key, AuthorizationService.Action.VIEW_MEMBERS)) {
            // Чужие PROCESS только там, где вызывающий — участник. Без canOperate —
            // пусто (не раскрываем даже факта наличия чужих шаблонов).
            if (kind != null) {
                shared.addAll(presetRepository.findSharedAtBinding(
                    VariablePresetVisibility.PROCESS, key, kind,
                    normalizeStoredRef(kind, ref), me));
            } else {
                shared.addAll(presetRepository.findSharedInKeys(
                    VariablePresetVisibility.PROCESS, List.of(key), me));
            }
        }
        List<VariablePresetEntity> out = new ArrayList<>(own.size() + shared.size());
        for (VariablePresetEntity e : own) {
            if (matches(e, key, kind, ref)) {
                out.add(e);
            }
        }
        for (VariablePresetEntity e : shared) {
            if (matches(e, key, kind, ref) && out.stream().noneMatch(x -> x.getId().equals(e.getId()))) {
                out.add(e);
            }
        }
        return out;
    }

    @Transactional
    public VariablePresetEntity create(Principal principal, PresetPayload payload) {
        requireAuth(principal);
        requireCanLaunch(principal, payload.processDefinitionKey());
        List<VariablePresetValidator.FieldError> errors = new ArrayList<>();
        errors.addAll(VariablePresetValidator.validateName(payload.name()));
        errors.addAll(VariablePresetValidator.validateBinding(
            payload.processDefinitionKey(), payload.targetKind(), payload.targetRef()));
        VariablePresetValidator.VariablesResult vars =
            VariablePresetValidator.parseAndValidate(payload.variablesJson());
        errors.addAll(vars.errors());
        failOnErrors(errors);
        UUID owner = actorUserId(principal);
        if (presetRepository.countByOwnerUserIdAndProcessDefinitionKey(
                owner, payload.processDefinitionKey()) >= VariablePresetValidator.MAX_PRESETS_PER_KEY_OWNER) {
            throw new ApiException(HttpStatus.CONFLICT, "PRESET_LIMIT_EXCEEDED",
                "Too many presets for this process (max "
                    + VariablePresetValidator.MAX_PRESETS_PER_KEY_OWNER + ")",
                Map.of("processDefinitionKey", payload.processDefinitionKey()));
        }
        String storedRef = normalizeStoredRef(payload.targetKind(), payload.targetRef());
        if (presetRepository.existsByOwnerUserIdAndProcessDefinitionKeyAndTargetKindAndTargetRefAndName(
                owner, payload.processDefinitionKey(), payload.targetKind(), storedRef, payload.name().trim())) {
            throw conflict("A preset with this name already exists here");
        }
        VariablePresetEntity entity = new VariablePresetEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessDefinitionKey(payload.processDefinitionKey());
        entity.setTargetKind(payload.targetKind());
        entity.setTargetRef(storedRef);
        entity.setName(payload.name().trim());
        entity.setDescription(blankToNull(payload.description()));
        entity.setVariables(VariablePresetValidator.toJson(vars.variables()));
        entity.setOwnerUserId(owner);
        entity.setVisibility(payload.visibility() == null
            ? VariablePresetVisibility.PRIVATE : payload.visibility());
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
        return entity;
    }

    @Transactional
    public VariablePresetEntity update(Principal principal, UUID id, String name, String description,
            String variablesJson, VariablePresetVisibility visibility, int version) {
        requireAuth(principal);
        VariablePresetEntity entity = presetRepository.findById(id)
            .orElseThrow(VariablePresetService::notFound);
        if (!canRead(principal, entity)) {
            throw notFound();
        }
        if (!canEdit(principal, entity)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PRESET_FORBIDDEN",
                "Only the owner, a process admin or SUPER_ADMIN may change this preset",
                Map.of("id", id.toString()));
        }
        if (entity.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "PRESET_CONFLICT",
                "Preset was changed concurrently (expected version " + version
                    + ", actual " + entity.getVersion() + ")",
                Map.of("expectedVersion", version, "actualVersion", entity.getVersion()));
        }
        List<VariablePresetValidator.FieldError> errors = new ArrayList<>();
        String newName = entity.getName();
        if (name != null) {
            errors.addAll(VariablePresetValidator.validateName(name));
            newName = name.trim();
        }
        List<ProcessVariable> newVars = null;
        if (variablesJson != null) {
            VariablePresetValidator.VariablesResult vars =
                VariablePresetValidator.parseAndValidate(variablesJson);
            errors.addAll(vars.errors());
            newVars = vars.variables();
        }
        failOnErrors(errors);
        if (!newName.equals(entity.getName())
            && presetRepository.existsByOwnerUserIdAndProcessDefinitionKeyAndTargetKindAndTargetRefAndName(
                entity.getOwnerUserId(), entity.getProcessDefinitionKey(),
                entity.getTargetKind(), entity.getTargetRef(), newName)) {
            throw conflict("A preset with this name already exists here");
        }
        entity.setName(newName);
        if (description != null) {
            entity.setDescription(blankToNull(description));
        }
        if (newVars != null) {
            entity.setVariables(VariablePresetValidator.toJson(newVars));
        }
        if (visibility != null) {
            entity.setVisibility(visibility);
        }
        entity.setUpdatedAt(Instant.now());
        entity.setVersion(entity.getVersion() + 1);
        try {
            entity = presetRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw conflict("A preset with this name already exists here");
        }
        auditLogService.record(principal, "PRESET_UPDATE",
            entity.getProcessDefinitionKey(), entity.getId().toString());
        return entity;
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
