package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.PresetContract;
import com.zorrodev.bpm.contract.dto.CreatePresetDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.PresetHistoryEntryDTO;
import com.zorrodev.bpm.contract.dto.PresetImportDTO;
import com.zorrodev.bpm.contract.dto.PresetVisibilityDTO;
import com.zorrodev.bpm.contract.dto.UpdatePresetDTO;
import com.zorrodev.bpm.contract.dto.VariablePresetDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.VariablePresetEntity;
import com.zorrodev.bpm.engine.entity.VariablePresetHistoryEntity;
import com.zorrodev.bpm.engine.entity.VariablePresetTargetKind;
import com.zorrodev.bpm.engine.entity.VariablePresetVisibility;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.VariablePresetService;
import com.zorrodev.bpm.engine.service.VariablePresetValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WO-VT-1: REST шаблонов переменных. Тонкий фасад над
 * {@link VariablePresetService}: флаг, извлечение принципала, парсинг enum из
 * строк (неизвестное значение — машинная PRESET_VALIDATION_FAILED, а не 500),
 * маппинг entity → DTO. Вся авторизация — в сервисе (default DENY, G-L).
 *
 * <p>Флаг — ПЕРВЫМ, до аутентификации: выключенный API — 404 для всех, включая
 * анонимов (п.6 WO).
 */
@RestController
@RequiredArgsConstructor
public class PresetResource implements PresetContract {

    private final VariablePresetService presetService;
    private final HttpServletRequest request;

    /** WO-VT-1 п.6: ключ в ОБОИХ файлах (rest-properties + app-файл, урок C8-36 F-7). */
    @Value("${zorrobpm.ui.variable-presets.enabled:true}")
    private boolean presetsEnabled;

    // ------------------------------------------------------------------ contract

    @Override
    public List<VariablePresetDTO> listPresets(
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String ref) {
        requireEnabled();
        Principal principal = getPrincipal();
        VariablePresetTargetKind targetKind = parseKind(kind);
        List<VariablePresetEntity> entities =
            presetService.list(principal, blankToNull(key), targetKind, blankToNull(ref));
        return entities.stream().map(e -> toDto(e, isFavorite(principal, e))).toList();
    }

    @Override
    public VariablePresetDTO getPreset(@PathVariable UUID id) {
        requireEnabled();
        Principal principal = getPrincipal();
        VariablePresetEntity entity = presetService.get(principal, id);
        return toDto(entity, isFavorite(principal, entity));
    }

    @Override
    public VariablePresetDTO createPreset(@RequestBody CreatePresetDTO dto) {
        requireEnabled();
        Principal principal = getPrincipal();
        VariablePresetService.PresetPayload payload = new VariablePresetService.PresetPayload(
            dto.getProcessDefinitionKey(),
            parseKind(dto.getTargetKind()),
            dto.getTargetRef(),
            dto.getName(),
            dto.getDescription(),
            VariablePresetValidator.toJson(requireVariables(dto.getVariables(), "variables")),
            parseVisibility(dto.getVisibility()));
        VariablePresetEntity entity = presetService.create(principal, payload);
        return toDto(entity, false);
    }

    @Override
    public VariablePresetDTO updatePreset(@PathVariable UUID id, @RequestBody UpdatePresetDTO dto) {
        requireEnabled();
        Principal principal = getPrincipal();
        String variablesJson = dto.getVariables() == null
            ? null : VariablePresetValidator.toJson(requireVariables(dto.getVariables(), "variables"));
        VariablePresetEntity entity = presetService.update(principal, id,
            dto.getName(), dto.getDescription(), variablesJson,
            parseVisibility(dto.getVisibility()), dto.getVersion());
        return toDto(entity, isFavorite(principal, entity));
    }

    @Override
    public void deletePreset(@PathVariable UUID id) {
        requireEnabled();
        presetService.delete(getPrincipal(), id);
    }

    @Override
    public VariablePresetDTO importPreset(@RequestBody PresetImportDTO dto) {
        requireEnabled();
        Principal principal = getPrincipal();
        VariablePresetService.PresetPayload payload = new VariablePresetService.PresetPayload(
            dto.getProcessDefinitionKey(),
            parseKind(dto.getTargetKind()),
            dto.getTargetRef(),
            dto.getName(),
            dto.getDescription(),
            VariablePresetValidator.toJson(requireVariables(dto.getVariables(), "variables")),
            parseVisibility(dto.getVisibility()));
        VariablePresetEntity entity = presetService.create(principal, payload);
        return toDto(entity, false);
    }

    @Override
    public PresetImportDTO exportPreset(@PathVariable UUID id) {
        requireEnabled();
        VariablePresetEntity entity = presetService.get(getPrincipal(), id);
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

    @Override
    public List<PresetHistoryEntryDTO> presetHistory(@PathVariable UUID id) {
        requireEnabled();
        Principal principal = getPrincipal();
        List<VariablePresetHistoryEntity> rows = presetService.history(principal, id);
        return rows.stream().map(r -> {
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

    @Override
    public IdDTO addFavorite(@PathVariable UUID id) {
        requireEnabled();
        presetService.setFavorite(getPrincipal(), id, true);
        IdDTO dto = new IdDTO();
        dto.setId(id);
        return dto;
    }

    @Override
    public void removeFavorite(@PathVariable UUID id) {
        requireEnabled();
        presetService.setFavorite(getPrincipal(), id, false);
    }

    @Override
    public VariablePresetDTO changeVisibility(
            @PathVariable UUID id, @RequestBody PresetVisibilityDTO dto) {
        requireEnabled();
        Principal principal = getPrincipal();
        VariablePresetEntity entity = presetService.changeVisibility(
            principal, id, parseVisibility(dto == null ? null : dto.getVisibility()));
        return toDto(entity, isFavorite(principal, entity));
    }

    // ------------------------------------------------------------------ helpers

    private void requireEnabled() {
        if (!presetsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variable presets are disabled");
        }
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private boolean isFavorite(Principal principal, VariablePresetEntity entity) {
        if (principal == null) return false;
        try {
            return presetService.isFavorite(principal, entity.getId());
        } catch (ApiException e) {
            return false;
        }
    }

    private VariablePresetDTO toDto(VariablePresetEntity e, boolean favorite) {
        VariablePresetDTO dto = new VariablePresetDTO();
        dto.setId(e.getId());
        dto.setProcessDefinitionKey(e.getProcessDefinitionKey());
        dto.setTargetKind(e.getTargetKind().name());
        dto.setTargetRef(storedRefToApi(e.getTargetRef()));
        dto.setName(e.getName());
        dto.setDescription(e.getDescription());
        List<ProcessVariable> variables = VariablePresetValidator.parseStored(e.getVariables());
        dto.setVariables(variables);
        dto.setOwnerUserId(e.getOwnerUserId());
        dto.setVisibility(e.getVisibility().name());
        dto.setFavorite(favorite);
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setVersion(e.getVersion());
        return dto;
    }

    /** Хранение '' → API null (см. комментарий changeset 20261007-120). */
    private static String storedRefToApi(String stored) {
        return stored == null || stored.isEmpty() ? null : stored;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static VariablePresetTargetKind parseKind(String kind) {
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

    private static VariablePresetVisibility parseVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) return null;
        try {
            return VariablePresetVisibility.valueOf(visibility.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRESET_VALIDATION_FAILED",
                "Preset validation failed",
                Map.of("fields", List.of(
                    Map.of("field", "visibility", "message", "unknown visibility '" + visibility + "'"))));
        }
    }

    private static List<ProcessVariable> requireVariables(List<ProcessVariable> variables, String field) {
        if (variables == null || variables.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRESET_VALIDATION_FAILED",
                "Preset validation failed",
                Map.of("fields", List.of(
                    Map.of("field", field, "message", "variables must be a non-empty array"))));
        }
        return variables;
    }
}
