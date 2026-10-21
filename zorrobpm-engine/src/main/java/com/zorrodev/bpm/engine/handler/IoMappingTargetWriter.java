package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WO-C8-58: target ioMapping с точкой создаёт ВЛОЖЕННЫЙ контекст — паритет живого
 * Zeebe 8.5.0.
 *
 * <p>Правила — измеренные на живом оракуле 8.5.0
 * ({@code docs/analysis/c8-oracle/feel-iomapping-static-nested.js}, пробы
 * {@code tgt-nested}, {@code tgt-nested-deep}, {@code tgt-nested-parent-string},
 * {@code tgt-nested-parent-ctx}, {@code tgt-nested-sibling}):
 *
 * <ul>
 *   <li>target {@code a.b} → {@code {"a":{"b":1}}} — НЕ плоский ключ {@code "a.b"};</li>
 *   <li>target {@code a.b.c} → {@code {"a":{"b":{"c":1}}}} — вложенность по всем
 *       сегментам;</li>
 *   <li>если родитель уже есть (строкой ИЛИ контекстом) — родитель
 *       <b>ЗАМЕНЯЕТСЯ</b>: запись идёт в скоуп элемента, а значение родителя из
 *       внешнего скоупа им затеняется (это и есть «замена», а не слияние);</li>
 *   <li>два маппинга в ОДИН родителя ({@code a.b} и {@code a.c}) →
 *       {@code {"a":{"b":1,"c":2}}} — оба выживают: контексты РОДНИТЕЛЕЙ
 *       накапливаются внутри одного применения ioMapping.</li>
 * </ul>
 *
 * <p>Значение листа кладится в контекст ТИПИЗИРОВАННЫМ (LONG/BOOLEAN/DOUBLE/JSON),
 * ровно как его видело бы FEEL-выражение: конвертация — общая
 * {@link ElementSupport#toFeelContext(List, ObjectMapper)}, второй копии типов нет.
 *
 * <p>Экземпляр живёт ОДНО применение ioMapping (один батч маппингов): накопление
 * родителей — свойство батча, а не переменная движка. Между разными применениями
 * (например, seed контейнера и маппинги элемента) накопления нет — там действует
 * правило «родитель заменяется».
 */
public class IoMappingTargetWriter {

    private final ObjectMapper objectMapper;
    /** Родитель → накопленный контекст (в пределах одного батча маппингов). */
    private final Map<String, Map<String, Object>> parents = new LinkedHashMap<>();

    public IoMappingTargetWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Переводит РЕЗУЛЬТАТ одного маппинга (имя = target) в переменную к записи.
     *
     * <p>Target без точки → переменная как есть (поведение не меняется). Target с
     * точкой → переменная-РОДИТЕЛЬ с JSON-контекстом.
     *
     * @param flat результат оценки маппинга (имя = target, значение типизировано)
     */
    public ProcessVariable persist(ProcessVariable flat) {
        String target = flat.getName();
        if (target == null) {
            return flat;
        }
        int dot = target.indexOf('.');
        if (dot <= 0 || dot == target.length() - 1) {
            // Обычный target: полностью перезаписывает одноимённого родителя
            // (правило «родитель ЗАМЕНЯЕТСЯ»), поэтому накопление контекста
            // сбрасывается.
            parents.remove(target);
            return flat;
        }
        String parent = target.substring(0, dot);
        List<String> path = segments(target.substring(dot + 1));
        Map<String, Object> context = parents.computeIfAbsent(parent, key -> new LinkedHashMap<>());
        setPath(context, path, rawValue(flat));
        ProcessVariable variable = new ProcessVariable();
        variable.setName(parent);
        variable.setType(ProcessVariableType.JSON);
        variable.setValue(writeJson(context));
        return variable;
    }

    /** Сегменты пути после первой точки (пустой сегмент игнорируется). */
    private static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result;
    }

    /**
     * Кладёт значение по пути в контекст, создавая промежуточные объекты.
     * Существующий не-объект на промежуточном уровне ЗАМЕНЯЕТСЯ объектом
     * (правило «родитель заменяется», а не «слить со строкой»).
     */
    @SuppressWarnings("unchecked")
    private static void setPath(Map<String, Object> context, List<String> path, Object value) {
        if (path.isEmpty()) {
            return;
        }
        Map<String, Object> node = context;
        for (int i = 0; i < path.size() - 1; i++) {
            Object child = node.get(path.get(i));
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                node.put(path.get(i), child);
            }
            node = (Map<String, Object>) child;
        }
        node.put(path.get(path.size() - 1), value);
    }

    /**
     * Типизированное значение переменной → обычное Java-значение для контекста.
     * Общая точка конвертации движка (обратная к {@code toFeelContext}) — второй
     * копии правил типов в проекте не появляется.
     */
    private Object rawValue(ProcessVariable flat) {
        Map<String, Object> context =
            ElementSupport.toFeelContext(List.of(flat), objectMapper);
        return context.get(flat.getName());
    }

    private String writeJson(Map<String, Object> context) {
        return objectMapper.writeValueAsString(context);
    }
}
