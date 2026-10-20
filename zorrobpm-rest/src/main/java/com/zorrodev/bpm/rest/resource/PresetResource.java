package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.PresetContract;
import com.zorrodev.bpm.contract.dto.CreatePresetDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.PresetHistoryEntryDTO;
import com.zorrodev.bpm.contract.dto.PresetImportDTO;
import com.zorrodev.bpm.contract.dto.PresetVisibilityDTO;
import com.zorrodev.bpm.contract.dto.UpdatePresetDTO;
import com.zorrodev.bpm.contract.dto.VariablePresetDTO;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.VariablePresetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * WO-VT-1: REST шаблонов переменных. Тонкий фасад над
 * {@link VariablePresetService} (как {@code MemberResource} над
 * {@code ProcessMemberService}): флаг, извлечение принципала, делегирование.
 * Весь entity-маппинг, парсинг enum и авторизация — в сервисе: граница WO-DEBT-7
 * запрещает этому пакету зависеть от {@code engine.entity/*}
 * (ловит {@code RestRepositoryBoundaryTest}).
 *
 * <p>Флаг — ПЕРВЫМ в контроллере, до сервисной логики: выключенный API —
 * 404 для всех АУТЕНТИФИЦИРОВАННЫХ (п.6 WO). Аноним до контроллера не
 * доходит вовсе — JwtAuthFilter отвечает 401 раньше (deny-by-default):
 * 404 «включая анонимов» было бы неверно, см. WO-VT-1 раунд 2 (Б-4).
 */
@RestController
@RequiredArgsConstructor
public class PresetResource implements PresetContract {

    private final VariablePresetService presetService;
    private final HttpServletRequest request;
    private final HttpServletResponse httpResponse;

    /** WO-VT-1 п.6: ключ живёт только в zorrobpm-rest.properties; в app-файл
     *  НЕ дублируется сознательно — G15 (env не в compose). Дефолт true в @Value. */
    @Value("${zorrobpm.ui.variable-presets.enabled:true}")
    private boolean presetsEnabled;

    @Override
    public List<VariablePresetDTO> listPresets(
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String ref) {
        requireEnabled();
        return presetService.list(getPrincipal(), blankToNull(key), blankToNull(kind), blankToNull(ref));
    }

    @Override
    public VariablePresetDTO getPreset(@PathVariable UUID id) {
        requireEnabled();
        return presetService.get(getPrincipal(), id);
    }

    /**
     * WO-AUDIT-14 (F-19): create → 201 + Location (контракт не тронут: тело то же,
     * статус/заголовок — через {@code @ResponseStatus} + инжектированный response;
     * тот же приём, что в {@code ProcessSubmissionResource}, WO-API-1).
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public VariablePresetDTO createPreset(@RequestBody CreatePresetDTO dto) {
        requireEnabled();
        VariablePresetDTO created = presetService.createFromContract(getPrincipal(), dto);
        setLocation(created);
        return created;
    }

    /**
     * WO-AUDIT-14 (F-19): {@code version} — обязательное поле ({@code Integer}
     * + {@code @NotNull}). Раньше это был {@code int}: пропущенный JSON-ключ
     * биндился в 0 и ПРОХОДИЛ оптимистическую блокировку на свежем пресете
     * (version=0) — то есть защита от гонки правок молча отключалась. Теперь
     * без версии — 400 VALIDATION_ERROR.
     */
    @Override
    public VariablePresetDTO updatePreset(@PathVariable UUID id,
            @Valid @RequestBody UpdatePresetDTO dto) {
        requireEnabled();
        return presetService.update(getPrincipal(), id, dto);
    }

    @Override
    public void deletePreset(@PathVariable UUID id) {
        requireEnabled();
        presetService.delete(getPrincipal(), id);
    }

    /** WO-AUDIT-14 (F-19): import — тоже create → 201 + Location. */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public VariablePresetDTO importPreset(@RequestBody PresetImportDTO dto) {
        requireEnabled();
        VariablePresetDTO imported = presetService.importPreset(getPrincipal(), dto);
        setLocation(imported);
        return imported;
    }

    @Override
    public PresetImportDTO exportPreset(@PathVariable UUID id) {
        requireEnabled();
        return presetService.exportPreset(getPrincipal(), id);
    }

    @Override
    public List<PresetHistoryEntryDTO> presetHistory(@PathVariable UUID id) {
        requireEnabled();
        return presetService.history(getPrincipal(), id);
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
        return presetService.changeVisibility(
            getPrincipal(), id, dto == null ? null : dto.getVisibility());
    }

    /** Location созданного шаблона (тот же путь, что GET /presets/{id}). */
    private void setLocation(VariablePresetDTO dto) {
        if (httpResponse != null && dto != null && dto.getId() != null) {
            httpResponse.setHeader("Location", "/presets/" + dto.getId());
        }
    }

    private void requireEnabled() {
        if (!presetsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variable presets are disabled");
        }
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
