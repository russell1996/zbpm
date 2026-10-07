package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.PresetHistoryEntryDTO;
import com.zorrodev.bpm.contract.dto.PresetImportDTO;
import com.zorrodev.bpm.contract.dto.PresetVisibilityDTO;
import com.zorrodev.bpm.contract.dto.CreatePresetDTO;
import com.zorrodev.bpm.contract.dto.UpdatePresetDTO;
import com.zorrodev.bpm.contract.dto.VariablePresetDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

import java.util.List;
import java.util.UUID;

/**
 * WO-VT-1: CRUD шаблонов переменных (фаза 1; движок не меняется — фронт
 * подставляет значения в уже существующие эндпоинты).
 */
public interface PresetContract {

    @GetExchange("/presets")
    List<VariablePresetDTO> listPresets(
        @RequestParam(required = false) String key,
        @RequestParam(required = false) String kind,
        @RequestParam(required = false) String ref);

    @GetExchange("/presets/{id}")
    VariablePresetDTO getPreset(@PathVariable UUID id);

    @PostExchange("/presets")
    VariablePresetDTO createPreset(@RequestBody CreatePresetDTO dto);

    @PutExchange("/presets/{id}")
    VariablePresetDTO updatePreset(@PathVariable UUID id, @RequestBody UpdatePresetDTO dto);

    @DeleteExchange("/presets/{id}")
    void deletePreset(@PathVariable UUID id);

    @PostExchange("/presets/import")
    VariablePresetDTO importPreset(@RequestBody PresetImportDTO dto);

    @GetExchange("/presets/{id}/export")
    PresetImportDTO exportPreset(@PathVariable UUID id);

    @GetExchange("/presets/{id}/history")
    List<PresetHistoryEntryDTO> presetHistory(@PathVariable UUID id);

    @PutExchange("/presets/{id}/favorite")
    IdDTO addFavorite(@PathVariable UUID id);

    @DeleteExchange("/presets/{id}/favorite")
    void removeFavorite(@PathVariable UUID id);

    @PutExchange("/presets/{id}/visibility")
    VariablePresetDTO changeVisibility(@PathVariable UUID id, @RequestBody PresetVisibilityDTO dto);
}
