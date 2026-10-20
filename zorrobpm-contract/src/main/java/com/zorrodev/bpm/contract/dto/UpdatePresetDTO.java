package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * WO-VT-1: правка шаблона. Все поля кроме {@code version} опциональны (null = не
 * менять); привязка здесь НЕ меняется (смена = новый шаблон).
 *
 * <p>WO-AUDIT-14 (F-19): {@code version} — {@code Integer} + {@code @NotNull}.
 * Примитив {@code int} был дефектом: пропущенный в JSON ключ биндился в 0, и
 * PUT без версии ПРОХОДИЛ оптимистическую блокировку на свежем пресете
 * (version=0) — защита от гонки правок молча отключалась, а на пресете с
 * version≥1 тот же запрос выглядел как «конфликт» (409), хотя клиент просто
 * забыл поле. Теперь версия обязательна: без неё — 400 VALIDATION_ERROR.
 * Явная {@code version: 0} на свежем пресете остаётся валидной (честное
 * совпадение версий, не лазейка).
 */
@Getter
@Setter
public class UpdatePresetDTO {
    private String name;
    private String description;
    private List<ProcessVariable> variables;
    private String visibility;

    @NotNull(message = "version is required")
    private Integer version;
}
