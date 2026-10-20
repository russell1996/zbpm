package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.IoMappingExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.bpmn.model.MessageEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.api.EvaluationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared utility methods for BPMN expression resolution, variable mapping, and type conversion.
 * Injected by both {@code ActivityServiceImpl} and {@code MultiInstanceExecutor} to avoid duplication.
 */
@Slf4j
@Component
public class ElementSupport {

    private final DBService dbService;
    private final ScriptService scriptService;
    /**
     * WO-ENG-20 (N09): прямое {@code FeelEngineApi}-поле заменено единой точкой входа
     * с лимитами — {@code =expr}-резолв ниже идёт через общий пул/timeout WO-REL-46,
     * а не напрямую в caller-thread без ограничения.
     */
    private final FeelBudget feelBudget;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * Business timezone for interpreting zone-naive FEEL date/time values.
     * Configured via {@code zorrobpm.business-timezone} (default {@code Asia/Almaty}).
     * WO-ENG-4: zone-naive LocalDateTime/LocalDate is interpreted in this zone rather than UTC.
     *
     * WO-QW-1 A-C-5e: final + explicit constructor (was non-final with
     * {@code @RequiredArgsConstructor}, so the field silently stayed null outside
     * Spring). The @Value default below is the single source of the default.
     */
    private final ZoneId businessZone;

    /**
     * WO-ENG-30: compat flag for the WO-ENG-29 strict behaviour. Default {@code false}
     * so already-deployed models referencing an OPTIONAL variable keep the legacy silent
     * {@code ""} instead of parking on an incident on the first real run after rollout.
     * Operators opt into the strict (Zeebe-closer) behaviour explicitly per installation.
     *
     * <p>WO-C8-54: the flag is OBSOLETE. The live Zeebe 8.5.0 measurement (the F1
     * campaign, {@code docs/analysis/c8-oracle/feel-iomapping.js}) shows that Zeebe
     * does NOT raise an incident for a missing variable in an io-mapping — it
     * evaluates it to a NULL VALUE and completes the job. The strict/lenient duality
     * of WO-ENG-29/30 was built on an unverified assumption about Zeebe; the
     * measurement disproves it. Both profiles now follow live 8.5, so the flag no
     * longer changes anything — it is kept (a deployment may still set it) and
     * reported as obsolete at startup. Removing the property itself is a separate
     * decision (owner) — see the report of WO-C8-54.</p>
     */
    private final boolean strictMissing;

    public ElementSupport(DBService dbService, ScriptService scriptService,
            FeelBudget feelBudget, tools.jackson.databind.ObjectMapper objectMapper,
            @Value("${zorrobpm.business-timezone:Asia/Almaty}") ZoneId businessZone,
            @Value("${zorrobpm.engine.io-mapping.strict-missing:false}") boolean strictMissing) {
        this.dbService = dbService;
        this.scriptService = scriptService;
        this.feelBudget = feelBudget;
        this.objectMapper = objectMapper;
        this.businessZone = businessZone;
        this.strictMissing = strictMissing;
        if (strictMissing) {
            log.warn("zorrobpm.engine.io-mapping.strict-missing=true is OBSOLETE since WO-C8-54: "
                + "a missing/unknown/incomparable variable in an io-mapping is a NULL VALUE "
                + "(live Zeebe 8.5), not an incident — both profiles now behave identically");
        }
    }

    /**
     * WO-REL-59 + WO-REL-63: единый порядок захвата instance→activity — тот же,
     * что у cancel-пути ({@code ProcessInstanceRuntimeOperationsImpl}:
     * {@code lockProcessInstance} → {@code cancelActiveActivities}).
     *
     * <p>Единственный способ взять activity-lock в этом движке. Прежний
     * {@code lockAndReload} (activity-only) удалён в WO-REL-63: после перевода
     * последних путей на этот метод у него не осталось ни одного
     * продакшн-вызова, а сам он — ровно та ловушка, которой оба раза ловился
     * ABBA-дедлок с отменой.
     *
     * <p>Почему activity-first неверен: {@code DBService.getActivityForUpdate}
     * на PostgreSQL даёт {@code FOR UPDATE OF} с одним алиасом и лочит ТОЛЬКО
     * строку activity (см. javadoc {@code ActivityRepository.findByIdForUpdate}
     * и {@code Rel59SqlProbePgIT}). Дальше путь исполнения в конце flow делает
     * {@code completeProcessInstance} — UPDATE строки {@code process_instances},
     * то есть просит ТОТ ЖЕ instance-row-lock, который уже держит отмена. Отмена
     * же берёт instance-lock первым и затем построчно UPDATE'ит activity-строки.
     * Получается activity→instance против instance→activity — классический
     * ABBA, проигравший получает {@code ERROR: deadlock detected} (SQLState
     * 40P01) через {@code deadlock_timeout} (~1с). Воспроизведено на реальном PG:
     * {@code Rel59CompleteCancelDeadlockPgIT} (complete) и
     * {@code Rel63RemainingAbbaDeadlockPgIT} (таймер / сообщение / граничное
     * событие) — 5–7 раундов deadlock на дереве до фикса, 0 после. С фиксом
     * второй участник просто ждёт коммита первого — сериализация вместо
     * deadlock.
     *
     * <p>Механика — прецедент {@code IncidentService.resolveIncident}:
     * plain read (нужен только processInstanceId) → instance-lock →
     * {@code getActivityForUpdate} (свежесть даёт сам захват: cancel пишет
     * activity-строки только под instance-lock; row-lock держится до коммита
     * той же транзакции — паттерн WO-ENG-19). Инвариант WO-REL-30 (ровно один
     * SELECT FOR UPDATE на activity) сохранён; добавлен ровно один FOR UPDATE на
     * instance — он и есть недостающая первая половина порядка.
     *
     * <p>Требует внешней транзакции ({@code getActivity} и
     * {@code lockProcessInstance} не открывают свои — JOIN-аннотация стоит на
     * {@code getActivityForUpdate}). Все живые вызывающие — доменные методы с
     * классовым {@code @Transactional} ({@code ActivityServiceImpl},
     * {@code CompletionService}, {@code EventTrigger}) — либо {@code
     * TimerJobExecutor.fire} с {@code REQUIRES_NEW}, так что в бою условие
     * выполняется.
     */
    public Activity lockInstanceFirst(UUID activityId) {
        Activity activity = dbService.getActivity(activityId);
        dbService.lockProcessInstance(activity.getProcessInstanceId());
        return dbService.getActivityForUpdate(activityId);
    }

    // ─── User task helpers ──────────────────────────────────────────────

    public String extractAssignee(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getAssignee)
            .orElse(null);
    }

    public String resolveAssignee(UUID processInstanceId, BpmnElementModel element) {
        String raw = extractAssignee(element);
        return resolveExpression(raw, processInstanceId);
    }

    /**
     * WO-IN-4: scope-версия {@link #resolveAssignee} для MI-инстансов.
     * {@code inputElement} биндится scope-локально ({@code setVariables(pi, scopeId, ...)};
     * root-контекст его не видит ({@code getVariables(pi)} = только scopeId IS NULL),
     * поэтому {@code =assigneeEmployeeId} в root-контексте даёт null и колонка
     * {@code user_tasks.assignee} остаётся NULL — внешняя команда получала total 0.
     * Контекст здесь — root+scope merge ({@code getVariables(pi, scopeId)}, scoped wins),
     * тот же, что видит живой FEEL-код инстанса. Остальные resolve-методы ниже
     * делегируют сюда же через raw-строки (P-24: один механизм, не копии).
     */
    public String resolveAssigneeInScope(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
        String raw = extractAssignee(element);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInScopeByNames(raw, processInstanceId, scopeId);
    }

    public String resolveCandidateGroupsInScope(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCandidateGroups)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInScopeByNames(raw, processInstanceId, scopeId);
    }

    public String resolveCandidateUsersInScope(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCandidateUsers)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInScopeByNames(raw, processInstanceId, scopeId);
    }

    /**
     * WO-IN-4: {@link #resolveExpression} на готовом списке переменных
     * (root+scope merge от вызывающего). Ветки и контракт неуспеха — те же:
     * {@code ${var}} → toString значения, {@code =feel} → через feelBudget,
     * провал → warn + null, литерал → как есть.
     *
     * <p>WO-IN-4 раунд 2, Б-1: значения подтягивает вызывающий ТОЛЬКО по именам
     * из выражения ({@code scopedVariablesFor}-образец WO-PERF-9) — нерелевантные
     * переменные scope в контекст не попадают, поэтому битый JSON в чужой
     * переменной результат не меняет (eager-парс всего scope давал инцидент
     * на весь MI-вход вместо деградации, как на master).
     */
    public String resolveExpressionInScope(String raw, UUID processInstanceId, List<ProcessVariable> scopeVariables) {
        if (raw == null || raw.isBlank()) return null;
        Map<String, Object> vars = toFeelContext(scopeVariables, objectMapper);

        if (raw.startsWith("${") && raw.endsWith("}")) {
            String varName = raw.substring(2, raw.length() - 1).trim();
            Object val = vars.get(varName);
            if (val == null) {
                log.warn("variable '{}' not found in instance {} scope, returning null", varName, processInstanceId);
                return null;
            }
            return val.toString();
        }

        if (raw.startsWith("=")) {
            // WO-ENG-20: через общий бюджет — тот же entry point, что root-версия.
            EvaluationResult result = feelBudget.evaluateExpression(raw.substring(1), vars);
            if (!result.isSuccess()) {
                log.warn("FEEL expression '{}' failed in instance {} scope: {}", raw, processInstanceId, result.failure());
                return null;
            }
            Object val = result.result();
            return val != null ? val.toString() : null;
        }

        return raw;
    }

    /** WO-IN-4: тот же scope-resolve, переменные подтягивает сам (root+scope merge).
     * WO-IN-4 раунд 2: живых вызывающих нет (MI-путь идёт через
     * {@link #resolveExpressionInScopeByNames}); оставлен для совместимости,
     * новые вызывающие обязаны использовать ByNames-вариант (Б-1: полный merge
     * scope eager-парсит чужие JSON и роняет резолв на битом нерелевантном). */
    public String resolveExpressionInScope(String raw, UUID processInstanceId, UUID scopeId) {
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInScope(raw, processInstanceId,
            dbService.getVariables(processInstanceId, scopeId));
    }

    /**
     * WO-IN-4 раунд 2, Б-1: scope-resolve ТОЛЬКО по именам из выражения
     * ({@link FeelVariableNames}, образец {@code scopedVariablesFor} WO-PERF-9).
     * Литералы (assignee="alice", статичный dueDate) чтения БД не требуют вовсе.
     * Нерелевантные переменные scope (в т.ч. битый JSON) не трогаются:
     * поведение при битом нерелевантном значении = как на master
     * (деградация в null, без инцидента на весь MI-вход).
     * Битое НУЖНОЕ значение сохраняет прежнюю семантику: FEEL/unmarshal
     * падает → warn + null → инцидент у вызывающего, как до фикса.
     */
    public String resolveExpressionInScopeByNames(String raw, UUID processInstanceId, UUID scopeId) {
        if (raw == null || raw.isBlank()) return null;
        if (!raw.startsWith("${") && !raw.startsWith("=")) {
            return raw;
        }
        return resolveExpressionInScope(raw, processInstanceId,
            dbService.getScopedVariablesByNames(processInstanceId, scopeId, FeelVariableNames.extract(raw)));
    }

    /**
     * WO-C8-40: chain-вариант {@link #resolveExpressionInScopeByNames} для элементов
     * ВНУТРИ подпроцессов: контекст — root + ВСЯ цепочка внешних скоупов +
     * собственный скоуп (nearest-wins), а не только root+own. Элемент верхнего
     * уровня (цепочка пуста) получает ровно тот же контекст, что ByNames-версия.
     * Чтения — pinpoint по именам из выражения (Б-1 IN-4: чужие JSON не трогаем).
     *
     * <p>WO-C8-42: собственный слой — scope-ONLY чтение
     * ({@code getScopeOnlyVariablesByNames}), а не root+own merge. Merge-ридер
     * возвращал root-строки для имён, которых own скоуп не писал, и они
     * перетирали байнды внешних MI-скоупов (stale root activity/loopCounter
     * затеняли цикл-переменную инстанса — дефект консьюмера 2026-10-14).
     * Root остаётся ПЕРВЫМ слоем (fallback для имён, которых нет ни в одном
     * скоупе — nearest-wins, паритет Zeebe S0/S8a-1).
     */
    public String resolveExpressionInChainScopeByNames(String raw, UUID processInstanceId, UUID activityId) {
        if (raw == null || raw.isBlank()) return null;
        if (!raw.startsWith("${") && !raw.startsWith("=")) {
            return raw;
        }
        Set<String> names = FeelVariableNames.extract(raw);
        Map<String, ProcessVariable> merged = new java.util.LinkedHashMap<>();
        for (ProcessVariable v : dbService.getVariablesByNames(processInstanceId, names)) {
            merged.put(v.getName(), v);
        }
        for (UUID scopeId : enclosingScopeChain(processInstanceId, activityId)) {
            // WO-C8-42: scope-ONLY слои (см. javadoc выше): merge-ридер подмешивал
            // stale root в КАЖДЫЙ enclosing-слой и перетирал байнды внешних
            // MI-скоупов — тот же класс дефекта для вложенных MI.
            for (ProcessVariable v : dbService.getScopeOnlyVariablesByNames(processInstanceId, scopeId, names)) {
                merged.put(v.getName(), v);
            }
        }
        for (ProcessVariable v : dbService.getScopeOnlyVariablesByNames(processInstanceId, activityId, names)) {
            merged.put(v.getName(), v);
        }
        return resolveExpressionInScope(raw, processInstanceId, new ArrayList<>(merged.values()));
    }

    /**
     * WO-IN-4 раунд 2, О-1: scope-версия {@link #resolveDueDate} для MI-инстансов
     * (зеркало {@link #resolveAssigneeInScope}: {@code =inputElement}-даты root
     * раньше не видел и давал null).
     */
    public String resolveDueDateInScope(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getDueDate)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInScopeByNames(raw, processInstanceId, scopeId);
    }

    /**
     * WO-IN-4 раунд 2, О-1: scope-версия {@link #resolveFollowUpDate} для
     * MI-инстансов (та же асимметрия, что у dueDate).
     */
    public String resolveFollowUpDateInScope(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getFollowUpDate)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInScopeByNames(raw, processInstanceId, scopeId);
    }

    /**
     * WO-IN-4 раунд 2, О-1: scope-версия {@link #resolveUserTaskPriorityOrThrow}
     * для MI-инстансов. Контракт неуспеха — тот же: битый/вне диапазона
     * priorityDefinition бросает {@code EngineException} (инцидент у вызывающего),
     * отсутствующий/пустой → дефолт 50. Резолв выражения — в scope, не в root.
     */
    public int resolveUserTaskPriorityInScopeOrThrow(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getPriority)
            .orElse(null);
        if (raw == null || raw.isBlank()) {
            return 50;
        }
        String resolved = resolveExpressionInScopeByNames(raw, processInstanceId, scopeId);
        Integer value = null;
        if (resolved != null && !resolved.isBlank()) {
            try {
                // WO-QW-6 (NEW3-08): та же замена parseInt → BigDecimal.intValueExact,
                // что в resolveUserTaskPriorityOrThrow (целая гарантия 0–100 ниже
                // не меняется, "5.50" по-прежнему явная ошибка).
                value = new java.math.BigDecimal(resolved.trim()).intValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                value = null;
            }
        }
        if (value == null || value < 0 || value > 100) {
            throw new com.zorrodev.bpm.contract.exception.EngineException(
                "User task '" + element.getId() + "' has a broken priorityDefinition '" + raw + "'"
                    + (resolved != null && !resolved.equals(raw) ? " (resolved to '" + resolved + "')" : "")
                    + " — priority must be an integer between 0 and 100");
        }
        return value;
    }

    /**
     * WO-C8-40: chain-версии резолва полей user task для {@code UserTaskHandler.createTaskRow}
     * (единый путь создания строк — обычный, фазовый, внутренний в подпроцессах).
     * Контекст — {@link #resolveExpressionInChainScopeByNames}; контракт неуспеха —
     * как у root/InScope-версий (priority бросает EngineException → инцидент).
     */
    public String resolveAssigneeInChainScope(UUID processInstanceId, UUID activityId, BpmnElementModel element) {
        String raw = extractAssignee(element);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInChainScopeByNames(raw, processInstanceId, activityId);
    }

    public String resolveCandidateGroupsInChainScope(UUID processInstanceId, UUID activityId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCandidateGroups)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInChainScopeByNames(raw, processInstanceId, activityId);
    }

    public String resolveCandidateUsersInChainScope(UUID processInstanceId, UUID activityId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCandidateUsers)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInChainScopeByNames(raw, processInstanceId, activityId);
    }

    public String resolveDueDateInChainScope(UUID processInstanceId, UUID activityId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getDueDate)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInChainScopeByNames(raw, processInstanceId, activityId);
    }

    public String resolveFollowUpDateInChainScope(UUID processInstanceId, UUID activityId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getFollowUpDate)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpressionInChainScopeByNames(raw, processInstanceId, activityId);
    }

    public int resolveUserTaskPriorityInChainScopeOrThrow(UUID processInstanceId, UUID activityId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getPriority)
            .orElse(null);
        if (raw == null || raw.isBlank()) {
            return 50;
        }
        String resolved = resolveExpressionInChainScopeByNames(raw, processInstanceId, activityId);
        Integer value = null;
        if (resolved != null && !resolved.isBlank()) {
            try {
                value = new java.math.BigDecimal(resolved.trim()).intValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                value = null;
            }
        }
        if (value == null || value < 0 || value > 100) {
            throw new com.zorrodev.bpm.contract.exception.EngineException(
                "User task '" + element.getId() + "' has a broken priorityDefinition '" + raw + "'"
                    + (resolved != null && !resolved.equals(raw) ? " (resolved to '" + resolved + "')" : "")
                    + " — priority must be an integer between 0 and 100");
        }
        return value;
    }

    public String resolveCandidateGroups(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCandidateGroups)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpression(raw, processInstanceId);
    }

    /**
     * WO-IN-3: кандидаты-ПОЛЬЗОВАТЕЛИ, зеркало {@link #resolveCandidateGroups} — та же выжимка
     * атрибута, то же разрешение выражения (в т.ч. {@code ${переменная}}), тот же null на
     * отсутствующем/пустом значении. Разница только в том, что значение уходит в таблицу
     * кандидатов (роль USER), а не в колонку: до этого WO атрибут парсился в BPMN-модель и там
     * и оставался (E-IN2-1), поэтому фильтровать по нему было нечем.
     */
    public String resolveCandidateUsers(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCandidateUsers)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpression(raw, processInstanceId);
    }

    public String resolveDueDate(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getDueDate)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpression(raw, processInstanceId);
    }

    public String resolveFollowUpDate(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getFollowUpDate)
            .orElse(null);
        if (raw == null || raw.isBlank()) return null;
        return resolveExpression(raw, processInstanceId);
    }

    // ─── Service task helpers ─────────────────────────────────────────

    /**
     * WO-C8-9: resolves {@code zeebe:jobPriorityDefinition} to an Integer job priority
     * (activation-order hint, delivered to the worker via JobDetailModel).
     * WO-C8-13 (A-1): the element is jobPriorityDefinition (priorityDefinition lives only on
     * user tasks); precedence is task value, then process-level default, then null.
     * Broken values resolve to null — never an exception or incident, same call as
     * WO-C8-8 made for broken dates (informational construct, must not break execution).
     */
    public Integer resolvePriority(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getPriority)
            .orElse(null);
        if (raw == null || raw.isBlank()) {
            raw = Optional.ofNullable(element.getProcessDefinition())
                .map(BpmnProcessDefinitionModel::getDefaultJobPriority)
                .orElse(null);
        }
        if (raw == null || raw.isBlank()) return null;
        String resolved = resolveExpression(raw, processInstanceId);
        if (resolved == null) return null;
        try {
            // WO-QW-6 (NEW3-08): FEEL отдаёт целые с масштабом ("5.00" после
            // WO-ENG-27, "5.0" от Double до этого) — parseInt на таком падает.
            // intValueExact: целые с лишними нулями парсятся, настоящая дробь
            // бросает ArithmeticException → тот же null-контракт, что раньше.
            return new java.math.BigDecimal(resolved.trim()).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            log.warn("jobPriorityDefinition '{}' resolved to non-integer '{}' — ignoring", raw, resolved);
            return null;
        }
    }

    /**
     * WO-C8-30: resolves {@code zeebe:priorityDefinition/@priority} of a user task to
     * an Integer task priority, evaluated at activation (docs: "Expressions are
     * evaluated when the user task is activated"). Absent/blank → docs default 50
     * ("If no value is provided, the default value is {@code 50}").
     *
     * <p>Unlike {@link #resolvePriority} (job dispatch hint — broken values resolve
     * to null, never an incident), a broken/out-of-range user-task priority THROWS
     * {@code EngineException} with the element, the raw value and the valid range:
     * WO-C8-30 demands an explicit incident, never a silent default. Callers turn
     * this into {@code errorActivity + createIncident} and halt activation.
     */
    public int resolveUserTaskPriorityOrThrow(UUID processInstanceId, BpmnElementModel element) {
        String raw = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getPriority)
            .orElse(null);
        if (raw == null || raw.isBlank()) {
            return 50;
        }
        String resolved = resolveExpression(raw, processInstanceId);
        Integer value = null;
        if (resolved != null && !resolved.isBlank()) {
            try {
                // WO-QW-6 (NEW3-08): см. resolvePriority выше — та же замена
                // parseInt → BigDecimal.intValueExact (целая гарантия 0–100
                // ниже не меняется, "5.50" по-прежнему явная ошибка).
                value = new java.math.BigDecimal(resolved.trim()).intValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                value = null;
            }
        }
        if (value == null || value < 0 || value > 100) {
            throw new com.zorrodev.bpm.contract.exception.EngineException(
                "User task '" + element.getId() + "' has a broken priorityDefinition '" + raw + "'"
                    + (resolved != null && !resolved.equals(raw) ? " (resolved to '" + resolved + "')" : "")
                    + " — priority must be an integer between 0 and 100");
        }
        return value;
    }

    // ─── Expression resolution ──────────────────────────────────────────

    /**
     * Resolves a raw BPMN expression string against process instance variables.
     * - ${var} → extract var name, look up in variables
     * - =expr → evaluate as FEEL expression
     * - plain string → return as-is (literal)
     */
    public String resolveExpression(String raw, UUID processInstanceId) {
        if (raw == null || raw.isBlank()) return null;

        if (raw.startsWith("${") && raw.endsWith("}")) {
            String varName = raw.substring(2, raw.length() - 1).trim();
            Map<String, Object> vars = variablesToMap(processInstanceId);
            Object val = vars.get(varName);
            if (val == null) {
                log.warn("variable '{}' not found in instance {}, returning null", varName, processInstanceId);
                return null;
            }
            return val.toString();
        }

        if (raw.startsWith("=")) {
            Map<String, Object> vars = variablesToMap(processInstanceId);
            // WO-ENG-20: через общий бюджет — timeout/bulkhead вместо прямого вызова
            // в caller-thread. Контракт неуспеха прежний: warn + null.
            EvaluationResult result = feelBudget.evaluateExpression(raw.substring(1), vars);
            if (!result.isSuccess()) {
                log.warn("FEEL expression '{}' failed in instance {}: {}", raw, processInstanceId, result.failure());
                return null;
            }
            Object val = result.result();
            return val != null ? val.toString() : null;
        }

        return raw;
    }

    public Map<String, Object> variablesToMap(UUID processInstanceId) {
        List<ProcessVariable> vars = dbService.getVariables(processInstanceId);
        Map<String, Object> map = new HashMap<>();
        for (ProcessVariable v : vars) {
            map.put(v.getName(), v.getValue());
        }
        return map;
    }

    // ─── Service task helpers ───────────────────────────────────────────

    public int serviceTaskRetries(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ext -> ext.getRetries())
            .orElse(3);
    }

    /** WO-EVT-9: stable job identifier from the BPMN model (zeebe:taskDefinition type analog). */
    public String serviceTaskJob(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getJob)
            .orElse(null);
    }

    /**
     * WO-C8-11: start execution listeners of a service task, in declaration order
     * (already filtered to {@code eventType="start"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> serviceTaskStartListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getStartListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-11b: end execution listeners of a service task, in declaration order
     * (already filtered to {@code eventType="end"} at parse time). Empty when absent —
     * callers must not touch end-listener state for such elements.
     */
    public List<ListenerModel> serviceTaskEndListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getEndListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    // ─── User task helpers ──────────────────────────────────────────────

    /**
     * WO-C8-21: creating task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="creating"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskCreatingListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCreatingListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-24: completing task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="completing"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskCompletingListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCompletingListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-28: assigning task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="assigning"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskAssigningListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getAssigningListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-28: updating task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="updating"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskUpdatingListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getUpdatingListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-28: canceling task listeners of a user task, in declaration order
     * (already filtered to {@code eventType="canceling"} at parse time). Empty when absent —
     * callers must not touch listener state for such elements.
     */
    public List<ListenerModel> userTaskCancelingListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .map(UserTaskExtensionModel::getCancelingListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    // ─── Listener helpers ─────────────────────────────────────────────

    /**
     * WO-C8-25: start execution listeners of a gateway/event element, in declaration
     * order (already filtered to {@code eventType="start"} at parse time). Empty when
     * absent — callers must not touch listener state for such elements. Separate reader
     * from {@link #serviceTaskStartListeners} ON PURPOSE (see
     * {@code BpmnElementExtensionModel.elementStartListeners}).
     */
    public List<ListenerModel> elementStartListeners(BpmnElementModel element) {
        List<ListenerModel> listeners = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getElementStartListeners)
            .orElse(null);
        return listeners == null ? List.of() : listeners;
    }

    /**
     * WO-C8-21r2: durable retry budget of one listener job — the model value, default 3
     * from the docs when the attribute is absent. Shared by all three listener kinds
     * (start/end/creating); callers set it wherever they set the phase index.
     */
    public int listenerBudget(ListenerModel listener) {
        return listener.retries() != null ? listener.retries() : 3;
    }

    // ─── IO mapping ────────────────────────────────────────────────────

    /**
     * JSON-маппер движка — для {@link IoMappingTargetWriter} (вложенный target).
     * Отдаётся, а не дублируется: вторая копия маппера разъехалась бы по
     * настройкам (P-24 — одна точка на механизм).
     */
    public tools.jackson.databind.ObjectMapper objectMapper() {
        return objectMapper;
    }

    /**
     * WO-C8-40: chain-bound seed входных маппингов контейнера подпроцесса для
     * MI-инстанса. Контекст оценки — цепочка внешних скоупов + собственный
     * (nearest-wins, как {@link #visibleVariables}), поэтому seed видит
     * и root-переменные, и привязку цикла инстанса; запись — в собственный скоуп.
     * Отличие от {@link #applyIoMappings}(…, true): та оценивает только
     * root+own, чей merge НЕ содержит outer-scope биндинги других имён —
     * для MI-инстанса это та же видимость, что у внутренних элементов
     * ({@link #visibleVariables}), и seed обязан её повторять.
     */
    public void applyChainedIoInputs(UUID processInstanceId, UUID activityId, BpmnElementModel element) {
        IoMappingExtensionModel io = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getIoMappingExtension)
            .orElse(null);
        if (io == null || io.getInputs() == null || io.getInputs().isEmpty()) {
            return;
        }
        List<ProcessVariable> variables = visibleVariables(processInstanceId, activityId);
        IoMappingTargetWriter writer = new IoMappingTargetWriter(objectMapper);
        Map<String, ProcessVariable> results = new LinkedHashMap<>();
        for (IoMappingExtensionModel.Mapping mapping : io.getInputs()) {
            ProcessVariable result = evaluateMapping(mapping, variables);
            if (result != null) {
                // WO-C8-58: target с точкой → вложенный контекст (Zeebe 8.5).
                results.put(result.getName(), writer.persist(result));
            }
        }
        if (!results.isEmpty()) {
            dbService.setVariables(processInstanceId, activityId, new ArrayList<>(results.values()));
            log.info("{}: Applied {} chained input mapping(s) at {} (scope {})",
                processInstanceId, results.size(), element.getId(), activityId);
        }
    }

    /**
     * WO-C8-40 (D1): запись выходов ioMapping внутреннего элемента. Внутри
     * MI-подпроцесса выходы пишутся в скоуп инстанса подпроцесса (ближайший
     * внешний SUB_PROCESS-контейнер с MI-признаком по лексической цепочке
     * {@code parentContainerElementId} — WO-C8-38), а не в root — иначе
     * lane-local переменные параллельных инстансов перезаписывают друг друга.
     * Для НЕ-MI подпроцессов и вне подпроцессов — прежняя запись в root
     * (нулевой риск регрессии, WO-DIFF-1/seed-инпуты контейнера не тронуты).
     * Лексический контейнер маппится на runtime scope activity через
     * {@link #enclosingScopeChain} (совпадение по bpmnElementId — тот же приём,
     * что {@link #filterActivitiesInScope}).
     *
     * <p>WO-C8-53: оценка — через единый ordered-механизм
     * {@link #applyOrderedOutputs} (target виден последующим маппингам,
     * live Zeebe 8.5); write scope прежний.
     */
    public void applyScopedOutputs(UUID processInstanceId, UUID activityId,
            BpmnElementModel element, BpmnProcessDefinitionModel bpmn) {
        if (!hasOutputMappings(element)) {
            return;
        }
        applyOrderedOutputs(processInstanceId, activityId, element,
            nearestMiSubProcessScope(processInstanceId, activityId, element, bpmn), null);
    }

    /**
     * WO-C8-40 (D1): runtime scope activity ближайшего внешнего MI-подпроцесса —
     * null, если элемент не внутри MI-подпроцесса. Лексика (модель) решает,
     * КАКОЙ контейнер MI; цепочка скоупов (БД) — КАКАЯ его runtime-строка.
     */
    public UUID nearestMiSubProcessScope(UUID processInstanceId, UUID activityId,
            BpmnElementModel element, BpmnProcessDefinitionModel bpmn) {
        String containerId = element.getParentContainerElementId();
        while (containerId != null) {
            BpmnElementModel container = bpmn.getElement(containerId);
            if (container == null) {
                return null;
            }
            if (container.getType() == BpmnElementType.SUB_PROCESS
                && container.getExtensions() != null
                && container.getExtensions().getMultiInstanceExtension() != null) {
                break;
            }
            containerId = container.getParentContainerElementId();
        }
        if (containerId == null) {
            return null;
        }
        final String miContainerId = containerId;
        for (UUID scopeId : enclosingScopeChain(processInstanceId, activityId)) {
            Activity scope = dbService.getActivity(scopeId);
            if (scope != null && miContainerId.equals(scope.getBpmnElementId())) {
                return scopeId;
            }
        }
        return null;
    }

    /**
     * WO-C8-41 (F1, oracle S1/S2b): скоуп записи completion-переменных элемента.
     * Внутри MI-подпроцесса — скоуп его инстанса (тот же, что D1 для выходов):
     * Zeebe держит completion-переменные инстанс-локально (S1: соседняя ветка
     * видит СВОЙ tu; S1-4: за телом их нет), root-write давал гонку
     * «последний завершивший перезаписывает всех». Вне MI-подпроцесса — null
     * (историческая запись в root, S0-контроль: без MI переменные видны
     * downstream). Для НЕ-MI подпроцессов тоже null — их паритет не прозондирован,
     * поведение не меняем.
     */
    public UUID miCompletionScopeOrNull(UUID processInstanceId, UUID activityId,
            BpmnElementModel element, BpmnProcessDefinitionModel bpmn) {
        return nearestMiSubProcessScope(processInstanceId, activityId, element, bpmn);
    }

    /**
     * WO-C8-41 (F1, oracle S2b): есть ли у элемента output mappings. С маппингами
     * сырые completion-переменные НЕ persist'ятся (маппинг — проекция: S2b
     * показывает tuLocal без сырого tu); без маппингов сырьё сливается в скоуп
     * инстанса (S1: w2 видит tu своей ветки).
     */
    public boolean hasOutputMappings(BpmnElementModel element) {
        IoMappingExtensionModel io = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getIoMappingExtension)
            .orElse(null);
        return io != null && io.getOutputs() != null && !io.getOutputs().isEmpty();
    }

    /**
     * WO-C8-53 (F1 корень 5, live Zeebe 8.5.0): ЕДИНЫЙ ordered-механизм
     * output-mapping'ов. Каждый маппинг оценивается ПО ПОРЯДКУ декларации,
     * и его target становится виден ПОСЛЕДУЮЩИМ маппингам того же элемента
     * (live 8.5: output-other-output → o3 читает o1, записанный маппингом выше;
     * фиксированный снапшот контекста давал strict-инцидент на o1 / lenient "").
     * В записывающий скоуп уходят ТОЛЬКО mapped target'ы — сырые
     * completion-переменные в parent не сливаются (live 8.5: output-completion
     * → c не доходит до root).
     *
     * <p>Параметризация — три точки входа, одно ядро:
     * {@link #applyScopedOutputs} (write = ближайший MI-scope, иначе root),
     * {@link #applyScopedOutputsWithExtra} (тот же write + overlay сырых
     * completion-переменных, S2b) и {@link #applyIoMappings}(…, false)
     * (write = root: не-MI-контейнеры/топ-уровень).
     *
     * <p>Ошибка оценки (strict-missing / hard FEEL failure) пробрасывает
     * {@code FeelEvaluationException} — как и в прежнем пути (инцидент
     * поднимает вызывающий хвост CompletionService, ENG-29/30-семантика
     * {@link #evaluateMapping} не меняется).
     */
    public void applyOrderedOutputs(UUID processInstanceId, UUID activityId,
            BpmnElementModel element, UUID writeScope, List<ProcessVariable> overlay) {
        IoMappingExtensionModel io = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getIoMappingExtension)
            .orElse(null);
        if (io == null || io.getOutputs() == null || io.getOutputs().isEmpty()) {
            return;
        }
        List<ProcessVariable> variables = visibleVariables(processInstanceId, activityId);
        if (overlay != null && !overlay.isEmpty()) {
            // S2b: сырые completion-переменные НЕ persist'ятся (маппинг —
            // проекция), но обязаны быть видны оценке — overlay побеждает
            // (свежее значение завершения).
            Map<String, ProcessVariable> merged = new java.util.LinkedHashMap<>();
            for (ProcessVariable v : variables) {
                merged.put(v.getName(), v);
            }
            for (ProcessVariable v : overlay) {
                merged.put(v.getName(), v);
            }
            variables = new ArrayList<>(merged.values());
        }
        List<ProcessVariable> results = new ArrayList<>();
        IoMappingTargetWriter writer = new IoMappingTargetWriter(objectMapper);
        Map<String, ProcessVariable> persisted = new LinkedHashMap<>();
        for (IoMappingExtensionModel.Mapping mapping : io.getOutputs()) {
            ProcessVariable result = evaluateMapping(mapping, variables);
            if (result != null) {
                // WO-C8-58: target с точкой → вложенный контекст; в БАТЧЕ накопление
                // родителей (два маппинга в одного родителя → оба выживают, Zeebe 8.5).
                ProcessVariable stored = writer.persist(result);
                persisted.put(stored.getName(), stored);
                results.add(stored);
                // Ordered: target немедленно виден последующим маппингам
                // (замена существующего имени — не дубль).
                List<ProcessVariable> next = new ArrayList<>(variables);
                boolean replaced = false;
                for (int i = 0; i < next.size(); i++) {
                    if (stored.getName().equals(next.get(i).getName())) {
                        next.set(i, stored);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    next.add(stored);
                }
                variables = next;
            }
        }
        if (!results.isEmpty()) {
            if (writeScope == null) {
                dbService.setVariables(processInstanceId, new ArrayList<>(persisted.values()));
            } else {
                dbService.setVariables(processInstanceId, writeScope, new ArrayList<>(persisted.values()));
            }
            log.info("{}: Applied {} ordered output mapping(s) at {} (write scope {})",
                processInstanceId, persisted.size(), element.getId(),
                writeScope == null ? "root" : writeScope);
        }
    }

    /**
     * WO-C8-53 (F1 корень 6, регрессия MiReenter, 2026-10-19): куда писать
     * СЫРЫЕ completion-переменные элемента, когда overlay-проекция
     * (MI-подпроцесс + маппинги) не применяется. Позиция на ПЕРЕМЕННУЮ, не
     * на элемент:
     * <ul>
     *  <li>внутри MI-подпроцесса — ВСЁ в скоуп инстанса (C8-41 S1, измерено —
     *      поведение не меняется);</li>
     *  <li>элемент несёт output-маппинги — ВСЁ в собственный activity scope:
     *      element-local, сырьё в parent не течёт (live 8.5: output-completion
     *      → c не в root); удаляется в конце хвоста (deleteVariables);</li>
     *  <li>элемент — инстанс МИ без маппингов — ТОЛЬКО переменная
     *      {@code outputElement} (простой FEEL-идентификатор) в собственный
     *      activity scope (live 8.5: r не в следующем job и не в dump,
     *      складывается в outputCollection); ОСТАЛЬНОЕ сырьё — в root:
     *      поведение «сырьё завершения MI-задачи видно downstream после
     *      join» НЕ зондировалось в кампании Ф1 и держит работающие прод-
     *      паттерны (gwDecision по {@code action} в controlProcess —
     *      MiReenterIntegrationTests); в Zeebe оно было бы instance-local —
     *      зафиксировано как остаточное расхождение (отчёт, ВЛИЯНИЕ НА ПРОД);</li>
     *  <li>иначе — ВСЁ в root (историческое S0, не меняется).</li>
     * </ul>
     */
    public record RawPlacement(UUID localScope, boolean allLocal, java.util.Set<String> localNames) {
        public boolean rootOnly() {
            return localScope == null && localNames.isEmpty();
        }
    }

    public RawPlacement completionRawPlacement(UUID processInstanceId, UUID activityId,
            BpmnElementModel element, BpmnProcessDefinitionModel bpmn) {
        UUID miScope = nearestMiSubProcessScope(processInstanceId, activityId, element, bpmn);
        if (miScope != null) {
            return new RawPlacement(miScope, true, java.util.Set.of());
        }
        if (hasOutputMappings(element)) {
            return new RawPlacement(activityId, true, java.util.Set.of());
        }
        if (element.getExtensions() != null
            && element.getExtensions().getMultiInstanceExtension() != null) {
            String out = element.getExtensions().getMultiInstanceExtension().getOutputElement();
            if (out != null && !out.isBlank() && out.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                return new RawPlacement(activityId, false, java.util.Set.of(out));
            }
        }
        return new RawPlacement(null, false, java.util.Set.of());
    }

    /**
     * Раскладывает сырые completion-переменные по позициям
     * {@link #completionRawPlacement}: allLocal → одна запись в localScope
     * (null = root), иначе раздельно: localNames → localScope, остальное →
     * root. Единая точка записи для обоих хвостов (user/service task).
     */
    public void writeCompletionRaw(UUID processInstanceId, UUID activityId,
            BpmnElementModel element, BpmnProcessDefinitionModel bpmn, List<ProcessVariable> variables) {
        RawPlacement raw = completionRawPlacement(processInstanceId, activityId, element, bpmn);
        if (variables == null || variables.isEmpty()) {
            return;
        }
        if (raw.allLocal()) {
            if (raw.localScope() == null) {
                dbService.setVariables(processInstanceId, variables);
            } else {
                dbService.setVariables(processInstanceId, raw.localScope(), variables);
            }
            return;
        }
        List<ProcessVariable> local = new ArrayList<>();
        List<ProcessVariable> rest = new ArrayList<>();
        for (ProcessVariable v : variables) {
            if (raw.localNames().contains(v.getName())) {
                local.add(v);
            } else {
                rest.add(v);
            }
        }
        if (!local.isEmpty() && raw.localScope() != null) {
            dbService.setVariables(processInstanceId, raw.localScope(), local);
        }
        if (!rest.isEmpty()) {
            dbService.setVariables(processInstanceId, rest);
        }
    }

    /**
     * WO-C8-41 (F3/D1-уточнение): pinpoint chain-контекст для агрегации
     * outputElement — root + ВСЯ цепочка внешних скоупов + собственный
     * (nearest-wins), только имена из выражения. Замена root+own merge в
     * агрегации: outputElement MI-задачи внутри MI-подпроцесса ссылается на
     * completion-переменную, которая после F1 живёт в скоупе КОНТЕЙНЕРА
     * (own scope задачи её не содержит) — root+own давал бы null вместо значения.
     *
     * <p>WO-C8-42: собственный слой — scope-ONLY (та же причина, что
     * {@link #resolveExpressionInChainScopeByNames}: merge-ридер подмешивал
     * stale root и перетирал внешние скоупы).
     */
    public List<ProcessVariable> chainVariablesForNames(UUID processInstanceId, UUID activityId,
            java.util.Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        // WO-C8-45: то же слияние — через общий mergeChainLayers (единая точка
        // для activity- и token-входов): root первым слоем, дальше цепочка
        // внешних скоупов, свой скоуп последним (nearest-wins).
        return mergeChainLayers(processInstanceId, names,
            enclosingScopeChain(processInstanceId, activityId), activityId);
    }

    /**
     * WO-C8-45: token-вариант {@link #chainVariablesForNames} — ЕДИНЫЙ механизм
     * переменных для оценки УСЛОВИЙ (branch conditions), когда строки activity
     * ещё нет или её id неудобен: то же слияние (root + вся цепочка внешних
     * скоупов токена, nearest-wins, scope-ONLY слои C8-42), pinpoint только по
     * именам из выражения (Б-1 IN-4: чужие JSON не читаются и не парсятся —
     * битое нерелевантное значение не роняет оценку, как на master).
     *
     * <p>Тот же алгоритм обхода, что {@code MultiInstanceExecutor.scopeChainOfToken}
     * (цепочка scopeActivityId-звеньев от токена к корню, outermost-first);
     * копия здесь — сознательно: MI-enter путь заморожен (V7), а условия обязаны
     * идти через одну точку. Null-safe к мокам (findToken → null/empty).
     *
     * <p>Вызывающие: {@code FlowNavigator.processFlow} (fallback без кэша),
     * {@code MultiInstanceExecutor.completionConditionMet} (containing scope
     * вложенного MI, C8-41 F3). Gateway-хендлеры передают кэш, собранный этим
     * же слиянием через activity-вход, — множества совпадают по построению
     * (gateway создан на том же токене).
     */
    public List<ProcessVariable> chainVariablesForTokenNames(UUID processInstanceId, UUID tokenId,
            java.util.Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return mergeChainLayers(processInstanceId, names, scopeChainOfToken(tokenId), null);
    }

    /**
     * WO-C8-45: общее слияние chain-контекста: root-слой первым (fallback для
     * имён, которых нет ни в одном скоупе), дальше scope-ONLY слои цепочки
     * (outermost-first), свой скоуп последним (nearest-wins). Без скоупов —
     * ровно root-проекция (поведение и стоимость как раньше).
     */
    private List<ProcessVariable> mergeChainLayers(UUID processInstanceId,
            java.util.Collection<String> names, List<UUID> chainOutermostFirst, UUID ownScopeId) {
        Map<String, ProcessVariable> merged = new java.util.LinkedHashMap<>();
        for (ProcessVariable v : dbService.getVariablesByNames(processInstanceId, names)) {
            merged.put(v.getName(), v);
        }
        for (UUID scopeId : chainOutermostFirst) {
            // WO-C8-42: scope-ONLY (см. resolveExpressionInChainScopeByNames).
            for (ProcessVariable v : dbService.getScopeOnlyVariablesByNames(processInstanceId, scopeId, names)) {
                merged.put(v.getName(), v);
            }
        }
        if (ownScopeId != null) {
            for (ProcessVariable v : dbService.getScopeOnlyVariablesByNames(processInstanceId, ownScopeId, names)) {
                merged.put(v.getName(), v);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * WO-C8-45: scope-цепочка токена (outermost-first) — тот же обход, что
     * {@link #enclosingScopeChain}, но от токена, а не от activity (у оценки
     * условия на токене строки activity может не быть вовсе).
     */
    public List<UUID> scopeChainOfToken(UUID tokenId) {
        List<UUID> chain = new ArrayList<>();
        java.util.Optional<Token> token = tokenId == null ? java.util.Optional.empty()
            : dbService.findToken(tokenId);
        while (token != null && token.isPresent()) {
            UUID scopeId = token.get().getScopeActivityId();
            if (scopeId != null && !chain.contains(scopeId)) {
                chain.add(scopeId);
            }
            UUID parentId = token.get().getParentId();
            token = parentId == null ? java.util.Optional.empty() : dbService.findToken(parentId);
        }
        java.util.Collections.reverse(chain);
        return chain;
    }

    /**
     * WO-C8-45: имена FEEL-переменных из условий исходящих потоков (сырые
     * выражения модели, '=' на месте — лексер его не берёт). Потоки без
     * условия/без модели вклада не дают (условие не оценивается — WO-DIFF-10).
     */
    public Set<String> flowConditionVariableNames(BpmnProcessDefinitionModel bpmn,
            java.util.Collection<String> flowIds) {
        Set<String> names = new java.util.LinkedHashSet<>();
        if (bpmn == null || flowIds == null) {
            return names;
        }
        for (String flowId : flowIds) {
            BpmnFlowModel flow = bpmn.getFlow(flowId);
            String expression = java.util.Optional.ofNullable(flow)
                .map(BpmnFlowModel::getConditionExpression)
                .map(com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel::getExpression)
                .filter(str -> !str.isEmpty())
                .map(str -> str.substring(1))
                .orElse(null);
            if (expression != null) {
                names.addAll(FeelVariableNames.extract(expression));
            }
        }
        return names;
    }

    /**
     * WO-C8-45: имена FEEL-переменных из условия conditional-события (catch или
     * boundary: {@code eventDefinition.expression}). Пусто, если условия нет, —
     * вызывающий тогда читает пустой контекст, а не весь root.
     */
    public Set<String> conditionalVariableNames(BpmnElementModel element) {
        String expression = java.util.Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel::getExpression)
            .filter(s -> !s.isBlank())
            .orElse(null);
        if (expression == null) {
            return Set.of();
        }
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        return FeelVariableNames.extract(expression);
    }

    /**
     * WO-C8-41 (F5, oracle S7-2): снос состояния MI-тела при его ОТМЕНЕ.
     * Zeebe не передаёт предсозданный outputCollection в родителя, когда тело
     * прервано (на cancel-ветке нет results), и при повторном входе массив
     * создаётся заново — значит, отмена удаляет и предсозданную коллекцию, и
     * batch-маркер входа (оба — в containing scope тела: innermost scope
     * токена входа, null = root). Удаляются ТОЛЬКО собственные переменные тела
     * (outputCollection + batch), остальной скоуп не трогаем. NO-OP без
     * outputCollection (удаляется только batch-маркер) и без MI-признака.
     * Вызывается из ВСЕХ путей отмены MI-тела: boundary (EventTrigger),
     * error/escalation-walk (ErrorEscalationThrower), terminate-in-scope
     * и cancel-end (EndEventHandler/CancelEndHandler). Раннее срабатывание
     * completionCondition — НЕ отмена (частичная агрегация сохраняется, S9-2),
     * здесь не вызывается.
     */
    public void deleteMiBodyState(UUID processInstanceId, UUID entryTokenId, BpmnElementModel miElement) {
        MultiInstanceExtensionModel mi = Optional.ofNullable(miElement.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .orElse(null);
        if (mi == null) {
            return;
        }
        UUID containingScope = null;
        try {
            containingScope = dbService.findToken(entryTokenId)
                .map(com.zorrodev.bpm.engine.dto.Token::getScopeActivityId).orElse(null);
        } catch (RuntimeException e) {
            containingScope = null;
        }
        if (mi.getOutputCollection() != null && !mi.getOutputCollection().isBlank()) {
            dbService.deleteVariable(processInstanceId, containingScope, mi.getOutputCollection());
        }
        dbService.deleteVariable(processInstanceId, containingScope, "_mi_batch_" + miElement.getId());
        log.info("{}/{}: MI body {} cancelled — dropped its output state in scope {}",
            processInstanceId, entryTokenId, miElement.getId(),
            containingScope == null ? "root" : containingScope);
    }

    /**
     * WO-C8-41 (F1, oracle S2a/S2b): вариант {@link #applyScopedOutputs} с явным
     * overlay completion-переменных в контекст оценки. Сырые completion-переменные
     * при ЕСТЬ маппингах НЕ persist'ятся (маппинг — проекция: S2b не показывает
     * сырой tu), но маппинг обязан их видеть (=tu читает tu-c завершения).
     * Контекст = visibleVariables + extra (extra побеждает — свежее значение
     * завершения). Запись — туда же, куда applyScopedOutputs (скоуп MI-инстанса
     * внутри MI-подпроцесса, иначе root). Без маппингов не вызывается
     * (сырьё идёт persist'ом в скоуп напрямую, S1).
     *
     * <p>WO-C8-53: оценка — через единый ordered-механизм
     * {@link #applyOrderedOutputs} (overlay как extra-слой, write scope прежний).
     */
    public void applyScopedOutputsWithExtra(UUID processInstanceId, UUID activityId,
            BpmnElementModel element, BpmnProcessDefinitionModel bpmn, List<ProcessVariable> extra) {
        if (!hasOutputMappings(element)) {
            return;
        }
        applyOrderedOutputs(processInstanceId, activityId, element,
            nearestMiSubProcessScope(processInstanceId, activityId, element, bpmn), extra);
    }

    public void applyIoMappings(UUID processInstanceId, UUID activityId, BpmnElementModel element, boolean inputs) {
        if (!inputs) {
            // WO-C8-53: выходы — через единый ordered-механизм (target виден
            // последующим маппингам, live Zeebe 8.5); write scope — root, как
            // исторически (не-MI-контейнеры/топ-уровень, WO-DIFF-1/C8-40).
            applyOrderedOutputs(processInstanceId, activityId, element, null, null);
            return;
        }
        IoMappingExtensionModel io = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getIoMappingExtension)
            .orElse(null);
        if (io == null) {
            return;
        }
        List<IoMappingExtensionModel.Mapping> mappings = io.getInputs();
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        // WO-DIFF-1 п.2: mappings see the element's own scope PLUS every enclosing
        // sub-process scope down to root (nearest-wins) — not just root+own.
        List<ProcessVariable> variables = visibleVariables(processInstanceId, activityId);
        IoMappingTargetWriter writer = new IoMappingTargetWriter(objectMapper);
        Map<String, ProcessVariable> results = new LinkedHashMap<>();
        for (IoMappingExtensionModel.Mapping mapping : mappings) {
            ProcessVariable result = evaluateMapping(mapping, variables);
            if (result != null) {
                // WO-C8-58: target с точкой → вложенный контекст (Zeebe 8.5).
                results.put(result.getName(), writer.persist(result));
            }
        }
        if (!results.isEmpty()) {
            dbService.setVariables(processInstanceId, activityId, new ArrayList<>(results.values()));
            log.info("{}: Applied {} input mapping(s) at {} (scope {})",
                processInstanceId, results.size(), element.getId(), activityId);
        }
    }

    /**
     * WO-DIFF-1 п.2: the variable context an ioMapping source evaluates against —
     * process root overlaid with the chain of enclosing sub-process scopes,
     * outermost first, the element's own scope last (nearest-wins for duplicate
     * names). Elements with no enclosing scope get exactly the historical
     * root+own merged view (same single repository read, zero behaviour change).
     *
     * <p>WO-C8-42: enclosing/own слои — scope-ONLY чтения. Merge-ридер
     * {@code getVariables(pi, scopeId)} возвращал root+scope и его root-строки
     * перетирали байнды внешних MI-скоупов на каждом слое (stale root
     * activity/loopCounter затеняли цикл-переменную инстанса — дефект
     * консьюмера 2026-10-14: decide нёс чужой activityUuid, повторный вход шёл
     * по устаревшему activity). При чистом root множество побайтово то же
     * (scope-строки те же, root уже лежит первым слоем).
     */
    public List<ProcessVariable> visibleVariables(UUID processInstanceId, UUID activityId) {
        List<UUID> enclosing = enclosingScopeChain(processInstanceId, activityId);
        if (enclosing.isEmpty()) {
            return dbService.getVariables(processInstanceId, activityId);
        }
        Map<String, ProcessVariable> merged = new java.util.LinkedHashMap<>();
        for (ProcessVariable v : dbService.getVariables(processInstanceId)) {
            merged.put(v.getName(), v);
        }
        for (UUID scopeId : enclosing) {
            for (ProcessVariable v : dbService.getScopeVariables(processInstanceId, scopeId)) {
                merged.put(v.getName(), v);
            }
        }
        for (ProcessVariable v : dbService.getScopeVariables(processInstanceId, activityId)) {
            merged.put(v.getName(), v);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * WO-DIFF-1 п.2: ids of the sub-process container activities enclosing the
     * given activity, outermost first, own scope excluded. Walked through the
     * token parent chain ({@code scopeActivityId} marks "inside this container");
     * null-safe ({@code findToken}) so a stale token reference yields an empty
     * chain instead of an exception (WO-REL-30 B-4 philosophy).
     */
    public List<UUID> enclosingScopeChain(UUID processInstanceId, UUID activityId) {
        com.zorrodev.bpm.engine.dto.Activity activity = dbService.getActivity(activityId);
        if (activity == null || activity.getToken() == null) {
            return List.of();
        }
        List<UUID> chain = new ArrayList<>();
        java.util.Optional<com.zorrodev.bpm.engine.dto.Token> token =
            dbService.findToken(activity.getToken());
        while (token.isPresent()) {
            UUID scopeId = token.get().getScopeActivityId();
            if (scopeId != null && !scopeId.equals(activityId) && !chain.contains(scopeId)) {
                chain.add(scopeId);
            }
            UUID parentId = token.get().getParentId();
            token = parentId == null ? java.util.Optional.empty() : dbService.findToken(parentId);
        }
        // Walked innermost-first; callers overlay outermost-first (nearest-wins).
        java.util.Collections.reverse(chain);
        return chain;
    }

    /**
     * WO-C8-34 (CR-02/CR-07): narrows an activity list to one execution scope.
     * An activity belongs to {@code scopeActivityId} when the scope container is
     * on its enclosing chain — i.e. its token is the scope token or a descendant
     * of it (parallel branches inside the scope fork child tokens, so a plain
     * token-equality check would miss the siblings; the chain walk covers them).
     * The container activity itself is NOT included (its own scope is the parent).
     * Used by terminate-in-subprocess (cancel only this scope) and by
     * compensation-throw candidate filtering (compensate only this scope).
     */
    public List<Activity> filterActivitiesInScope(
            UUID processInstanceId, List<Activity> activities, UUID scopeActivityId) {
        List<Activity> inScope = new ArrayList<>();
        for (Activity activity : activities) {
            if (activity == null || activity.getId() == null) {
                continue;
            }
            if (enclosingScopeChain(processInstanceId, activity.getId()).contains(scopeActivityId)) {
                inScope.add(activity);
            }
        }
        return inScope;
    }

    /**
     * WO-AUDIT-11 (F-09): ЭФФЕКТИВНЫЙ scope токена — его собственный
     * {@code scopeActivityId}, а при null — ближайший scope ВВЕРХ по цепочке токенов.
     *
     * <p>Форк-токен ({@code ParallelGatewayHandler}, неявный форк в
     * {@code FlowNavigator.proceedToOutgoing}, {@code InclusiveGatewayHandler}) создаётся с
     * {@code scopeActivityId = null}: ветка форка «вне скоупа» по своему токену, хотя
     * ФАКТИЧЕСКИ живёт внутри объемлющего подпроцесса. Поэтому собственного scope токена
     * недостаточно — нужен ближайший scope предка. Тот же обход цепочки, что у
     * {@link #enclosingScopeChain}, но по токенам.
     *
     * @return id activity-контейнера скоупа либо null (корневой скоуп / токен не найден)
     */
    public UUID effectiveScopeId(UUID tokenId) {
        UUID current = tokenId;
        while (current != null) {
            Token token = dbService.findToken(current).orElse(null);
            if (token == null) {
                return null;
            }
            if (token.getScopeActivityId() != null) {
                return token.getScopeActivityId();
            }
            current = token.getParentId();
        }
        return null;
    }

    /**
     * WO-AUDIT-11 (F-09): жив ли скоуп, в котором идёт разветвление.
     *
     * <p>CANCELLED-контейнер = скоуп ТЕРМИНИРОВАН (error/escalation-граница объемлющего
     * подпроцесса, terminate, отмена оператора) — активировать в нём больше нечего:
     * оставшиеся ветви форка не должны стартовать. Zeebe 8.5 (оракул
     * {@code docs/analysis/c8-oracle/scenarios/a11-fork-scope-boundary}): прерывающая
     * граница гасит скоуп целиком, job сестринской ветви не активируется.
     *
     * <p>Контейнер в статусе COMPLETED — это «скоуп вышел штатно», а не «погашен»: он не
     * является признаком смерти скоупа (и в такой ветке гасить нечего).
     */
    public boolean isScopeAlive(UUID processInstanceId, UUID tokenId) {
        UUID scopeId = effectiveScopeId(tokenId);
        if (scopeId == null) {
            return true; // корневой скоуп: гасить нечего
        }
        Activity scope = dbService.getActivity(scopeId);
        return scope == null || scope.getStatus() != ActivityStatus.CANCELLED;
    }

    /**
     * Evaluates one io-mapping {@code source} (FEEL, optional leading '=') against the given
     * variables and converts the result into a {@link ProcessVariable} named {@code target}.
     * Returns {@code null} for a malformed mapping (missing source/target) — callers skip it.
     * <p>Shared by {@link #applyIoMappings} (activity-scoped writes) and WO-ENG-11 call-activity
     * mappings: input mappings seed a not-yet-created child instance, output mappings override the
     * propagateAllChildVariables toggle — same evaluation pattern, different write scope.</p>
     * <p>WO-ENG-29: evaluation goes through the native FEEL API (same engine, same typed
     * context as the DMN-eval path — {@code FeelBudget}/{@code FeelEngineApi}, not the
     * lossy JSR-223 wrapper) so «evaluation failure» is distinguishable from «honest
     * null»: a failed evaluation (e.g. source references a variable absent from the job
     * result) throws {@link com.zorrodev.bpm.engine.service.FeelEvaluationException}
     * (incident path at callers), while a legitimate FEEL {@code null} (explicit
     * {@code =null} literal) still returns a null-valued variable as before, no
     * exception, no incident. Callers must NOT catch this into a silent default —
     * the input path relies on {@code ActivityServiceImpl.execute()}'s element-failure
     * catch → {@code incidentService.raiseIncident(...)}, the output path on
     * {@code CompletionService}'s symmetric catch in the service-task tail.</p>
     * <p>WO-ENG-30: the «variable not found» case (suppressed failures + null result)
     * throws only when {@code zorrobpm.engine.io-mapping.strict-missing=true}. With the
     * default {@code false} already-deployed models keep the pre-ENG-29 silent
     * {@code ""} behaviour (plus a WARN carrying source/target for future inventory
     * by log grep, no DB access needed). Default is {@code false} NOT because strict
     * is wrong — it is the correct behaviour — but because rolling out strict without
     * an inventory of live models parks them on incidents silently. A HARD evaluation
     * failure ({@code !isSuccess}, e.g. syntax errors — the Zeebe fix class) always
     * throws regardless of the flag: that is a genuine computation error, never an
     * «absent optional variable».</p>
     *
     * <p>WO-C8-54: the strict/lenient duality is GONE — the live Zeebe 8.5.0
     * measurement (F1 campaign, {@code docs/analysis/c8-oracle/feel-iomapping.js})
     * shows that Zeebe evaluates a missing variable, an unknown function, an
     * incomparable comparison and an out-of-range index to a NULL VALUE: the job is
     * created, the mapped variable is a JSON null, the process continues — no
     * incident. The strict premise of WO-ENG-29/30 («Zeebe raises an incident») was
     * never measured and is false, so both profiles now follow live 8.5:
     * <ul>
     *   <li>a HARD failure (syntax error — the Zeebe fix class, rejected at deploy
     *       by WO-C8-56) still throws → the incident path is unchanged;</li>
     *   <li>a SUPPRESSED failure with a null result (missing variable, unknown
     *       function, incomparable types, missing context key) → a null VALUE
     *       (JSON null), no incident — the live-8.5 behaviour;</li>
     *   <li>a value WITH a suppressed failure → the value (unchanged).</li>
     * </ul></p>
     * <p>WO-C8-55: a source WITHOUT a leading {@code =} is a STATIC VALUE, not a FEEL
     * expression — the rule live Zeebe 8.5 applies (measured on the live oracle,
     * {@code docs/analysis/c8-oracle/feel-iomapping-static-nested.js}): the source text is
     * delivered VERBATIM and is always a STRING — {@code hello} → {@code "hello"},
     * {@code present} → {@code "present"} even when a variable of that name exists,
     * {@code 42} → {@code "42"} (not the number 42), {@code {"a":1}} → the string
     * {@code "{\"a\":1}"} (not a parsed context), {@code "  hello  "} → the string with its
     * spaces kept. Before this the engine evaluated such a source as FEEL: a bare word became
     * a variable reference (resolved, or a null value), a number became a number, a JSON text
     * became a parsed context ({@code {"a":1}} IS legal FEEL — it deployed and delivered the
     * CONTEXT), and a value with a space in it ({@code hello world}) was refused at deploy as
     * unparseable FEEL.</p>
     *
     * <p>The mirror rule on the OUTPUT side is a DEPLOY refusal (live 8.5: an output source is
     * an EXPRESSION, never a static value — «Expected expression but found static value
     * 'hello'. An expression must start with '='»); the deploy gate is
     * {@code FeelSyntaxValidator}. A model deployed BEFORE that gate keeps running here with
     * the same static-value rule — the same treatment as every other already-deployed model
     * (the refusal is deploy-time only).</p>
     */
    public ProcessVariable evaluateMapping(IoMappingExtensionModel.Mapping mapping, List<ProcessVariable> variables) {
        if (mapping.getSource() == null || mapping.getTarget() == null || mapping.getTarget().isBlank()) {
            return null;
        }
        String source = mapping.getSource();
        if (source.isBlank()) {
            // WO-ENG-29: degenerate but previously-valid shape — legacy JSR-223 eval of
            // "" returned null → "" STRING variable; keep byte-identical, no incident.
            Object legacy = scriptService.evaluateExpression(source, variables);
            return toProcessVariable(mapping.getTarget(), legacy);
        }
        if (!source.startsWith("=")) {
            // WO-C8-55: a static value — the source text verbatim, always a STRING.
            // Not FEEL, not a variable name, not a number, not a parsed context.
            return toProcessVariable(mapping.getTarget(), source);
        }
        String expression = source.substring(1);
        Map<String, Object> context = toFeelContext(variables, objectMapper);
        // WO-ENG-20: same budget entry point (pool/timeout/bulkhead) as every other
        // FeelEngineApi caller — resolveExpression and DmnServiceImpl.
        org.camunda.feel.api.EvaluationResult result = feelBudget.evaluateExpression(expression, context);
        // WO-ENG-29: failure-vs-null distinction. HARD failure (!isSuccess, e.g.
        // syntax errors — the Zeebe fix class) always throws FeelEvaluationException
        // (incident path). A NO_VARIABLE_FOUND suppressed failure ALSO throws —
        // EXCEPT when the result carries a non-null VALUE (e.g. a fallback after
        // `??`/`default()`, or a value produced before the nested error): then the
        // failure is recorded but the expression yielded data, so callers proceed
        // with the value as before (legacy JSR-223 returned the value, never the
        // failure). A clean success with null result and EMPTY suppressed list is
        // honest null (explicit =null literal, present-but-null variable) — legacy
        // value conversion as before, no exception.
        // Verified against feel-engine 1.19.3 native API — see report probe table:
        // missing bare name → success/null + suppressed NO_VARIABLE_FOUND;
        // =null literal → success/null + EMPTY suppressed; syntax error → !success.
        // WO-ENG-29: failure-vs-null distinction. A HARD failure (!isSuccess, e.g.
        // syntax errors — the Zeebe fix class) throws FeelEvaluationException
        // (incident path) in BOTH modes.
        //
        // WO-C8-54: a SUPPRESSED failure with a null result is NOT an incident — it is
        // a null value (live Zeebe 8.5). The suppressed-failure set of the native API
        // is exactly the "the expression could not produce a value" class:
        // NO_VARIABLE_FOUND (a missing variable), NO_FUNCTION_FOUND (an unknown
        // function), NO_CONTEXT_ENTRY_FOUND (a missing context key), NOT_COMPARABLE
        // (values of different types). All of them evaluate to null in Zeebe, and the
        // job is created with a null variable. The WARN is kept (log-based inventory
        // of the models that hit the null path).
        boolean hardFailure = result.isFailure();
        if (hardFailure) {
            throw new com.zorrodev.bpm.engine.service.FeelEvaluationException(
                "io-mapping source '" + source + "' failed to evaluate for target '"
                    + mapping.getTarget() + "': " + result.failure());
        }
        Object value = result.result();
        if (value == null && !result.suppressedFailures().isEmpty()) {
            log.warn("io-mapping source '{}' evaluated to null for target '{}' ({} suppressed) "
                + "— Zeebe 8.5 parity: a null VALUE, no incident",
                source, mapping.getTarget(), result.suppressedFailures().mkString("; "));
        }
        return toProcessVariable(mapping.getTarget(), value);
    }

    /**
     * WO-ENG-29: FEEL variable context with real Java types (LONG → Long, DOUBLE →
     * BigDecimal, BOOLEAN → Boolean, JSON → Map/List, else raw string) — the same
     * conversion the legacy JSR-223 path applied in
     * {@code ScriptServiceImpl.buildContext} and the DMN path applies in
     * {@code DmnServiceImpl.toVariableMap}. Shared here so io-mapping evaluation
     * through the native API sees the same values as every other FEEL caller.
     * A variable set explicitly to a null VALUE stays a present-but-null entry
     * (FEEL resolves the name to null — honest null, not «not found»).
     */
    public static Map<String, Object> toFeelContext(List<ProcessVariable> variables,
            tools.jackson.databind.ObjectMapper mapper) {
        Map<String, Object> map = new HashMap<>();
        if (variables == null) {
            return map;
        }
        for (ProcessVariable variable : variables) {
            if (variable.getType() == ProcessVariableType.LONG) {
                map.put(variable.getName(), Long.valueOf(variable.getValue()));
            } else if (variable.getType() == ProcessVariableType.BOOLEAN) {
                map.put(variable.getName(), Boolean.valueOf(variable.getValue()));
            } else if (variable.getType() == ProcessVariableType.DOUBLE) {
                map.put(variable.getName(), new java.math.BigDecimal(variable.getValue()));
            } else if (variable.getType() == ProcessVariableType.JSON) {
                map.put(variable.getName(), mapper.readValue(variable.getValue(), Object.class));
            } else {
                map.put(variable.getName(), variable.getValue());
            }
        }
        return map;
    }

    // ─── Type conversion ────────────────────────────────────────────────

    /**
     * WO-ENG-25 (NEW-05): script-FEEL (JSR-223) отдаёт числа как
     * {@code scala.math.BigDecimal} — точное значение, но чужой тип: без
     * нормализации оно не узнаётся {@code java.math.BigDecimal}-спецветками
     * ниже и уходит в double-детур с потерей точности. {@code .bigDecimal()} —
     * точная конвертация без округления (scala держит те же unscaledValue и
     * scale, что java). Прямая ссылка на scala-тип — принятый паттерн этого
     * файла (см. {@code isStructuredResult} ниже).
     */
    static Object normalizeFeelNumber(Object result) {
        if (result instanceof scala.math.BigDecimal sbd) {
            return sbd.bigDecimal();
        }
        return result;
    }

    public ProcessVariable toProcessVariable(String name, Object result) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        // WO-ENG-25 (NEW-05): нормализация ДО любых проверок — иначе
        // scala.BigDecimal ≥2⁵³ с дробью идёт в isIntegral через double,
        // ошибочно считается «целым», и longValueExact падает на дробном
        // значении с ложным "outside LONG range".
        Object normalized = normalizeFeelNumber(result);
        if (normalized == null) {
            // WO-C8-54: a FEEL null is a VALUE, not an empty string. Live Zeebe 8.5
            // delivers it as a JSON null (measured: the job payload carries
            // {"r":null} for a missing variable, an unknown function, an
            // incomparable comparison, an out-of-range index and an explicit
            // =null). The legacy conversion turned it into a STRING "" — the
            // "null silently becomes an empty string" defect of WO-C8-54's root 1.
            // The only representation our typed variable model has for a JSON null
            // is type=JSON with the text "null" (the same one the multi-instance
            // outputCollection path already writes — see MultiInstanceExecutor).
            variable.setType(ProcessVariableType.JSON);
            variable.setValue("null");
            return variable;
        }
        if (normalized instanceof Boolean b) {
            variable.setType(ProcessVariableType.BOOLEAN);
            variable.setValue(b.toString());
        } else if (normalized instanceof Number number && isIntegral(number)) {
            // WO-ENG-21 (N10): целое вне диапазона Long — явный EngineException,
            // а не молчаливое усечение longValue() (у BigDecimal теряются старшие
            // биты, у double — насыщение к Long.MAX_VALUE; оба тихо меняют
            // значение). Контракт: integral && withinLongRange → LONG точно.
            variable.setType(ProcessVariableType.LONG);
            variable.setValue(Long.toString(longValueExact(name, number)));
        } else if (normalized instanceof Number number) {
            java.math.BigDecimal bd = (number instanceof java.math.BigDecimal x)
                ? x : java.math.BigDecimal.valueOf(number.doubleValue());
            variable.setType(ProcessVariableType.DOUBLE);
            variable.setValue(bd.toPlainString());
        } else if (isStructuredResult(normalized)) {
            variable.setType(ProcessVariableType.JSON);
            variable.setValue(objectMapper.writeValueAsString(toJavaStructure(normalized)));
        } else if (normalized instanceof java.time.temporal.TemporalAccessor temporal) {
            // WO-ENG-33: FEEL date/time-объект, дошедший досюда, сериализуем в
            // interoperable ISO-строку с офсетом — НЕ голым toString(). У
            // ZonedDateTime toString() даёт Java-формат с именем зоны
            // (2026-09-30T15:49:48.966494894+05:00[Asia/Almaty]), который не
            // парсит никто кроме Java (живой инцидент: Go-API ответило
            // extra text: "[Asia/Almaty]"). Контракт типа НЕ меняется: всё
            // равно STRING, меняется только содержимое для date-объектов.
            //
            // Слой 1 из WO (string(now()) средствами FEEL, суффикс @ZoneId) —
            // НЕ чиним: string() исполняется ВНУТРИ feel-scala 1.19.3 (та же
            // линия, что Camunda 8), это апстрим-паритет, руками в либу не
            // лезем. Сюда такие значения приходят уже готовыми String и идут
            // общим else ниже нетронутыми — специально.
            variable.setType(ProcessVariableType.STRING);
            variable.setValue(formatFeelDatetime(name, temporal));
        } else {
            variable.setType(ProcessVariableType.STRING);
            variable.setValue(normalized.toString());
        }
        return variable;
    }

    /**
     * WO-ENG-33: ISO-сериализация FEEL date/time-объектов в STRING-переменные.
     * {@code ZonedDateTime}/{@code OffsetDateTime} → {@code ISO_OFFSET_DATE_TIME}
     * ({@code ...+05:00}, RFC 3339 — ест Go-layout
     * {@code 2006-01-02T15:04:05Z07:00}); {@code Instant} → {@code ISO_INSTANT};
     * zone-naive {@code LocalDateTime}/{@code LocalDate}/{@code LocalTime} →
     * {@code ISO_LOCAL_*} БЕЗ офсета (зона неизвестна — честно, не выдумываем;
     * интерпретация naive-значений в businessZone живёт в
     * {@code computeDueAt}, здесь только формат). Неизвестный
     * {@code TemporalAccessor} — fail-closed явным {@code EngineException}, а не
     * молчаливый {@code toString()} с квадратными скобками: FEEL date/time
     * отдают только типы выше (Period/Duration — вообще не TemporalAccessor),
     * так что это охранник от будущих типов, не от текущего FEEL-набора.
     */
    static String formatFeelDatetime(String name, java.time.temporal.TemporalAccessor temporal) {
        if (temporal instanceof java.time.ZonedDateTime zdt) {
            return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt);
        }
        if (temporal instanceof java.time.OffsetDateTime odt) {
            return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(odt);
        }
        if (temporal instanceof java.time.Instant instant) {
            return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant);
        }
        if (temporal instanceof java.time.LocalDateTime ldt) {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(ldt);
        }
        if (temporal instanceof java.time.LocalDate ld) {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(ld);
        }
        if (temporal instanceof java.time.LocalTime lt) {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_TIME.format(lt);
        }
        if (temporal instanceof java.time.OffsetTime ot) {
            // FEEL time() с зоной: toString() здесь и так ISO (15:00+05:00,
            // без скобок) — фиксируем формат явно, инцидента нет.
            return java.time.format.DateTimeFormatter.ISO_OFFSET_TIME.format(ot);
        }
        throw new com.zorrodev.bpm.contract.exception.EngineException(
            "Variable '" + name + "' holds an unsupported date/time type "
                + temporal.getClass().getName()
                + " — refusing to store a non-interoperable toString() value");
    }

    /**
     * WO-ENG-21 (N10): точное целое в диапазоне Long. Целое ВНЕ диапазона
     * (например 2^63 из FEEL/DMN-результата) — EngineException с именем
     * переменной и границами, а не усечённое значение. Тот же тип исключения,
     * что у соседних числовых domain-ошибок (cardinality F23 в
     * {@code MultiInstanceExecutor}, priority WO-C8-30) — и та же маршрутизация
     * в вызывающих путях; откат в binary double для денег запрещён самим WO.
     */
    private long longValueExact(String name, Number number) {
        try {
            if (number instanceof java.math.BigDecimal bd) {
                return bd.longValueExact();
            }
            if (number instanceof java.math.BigInteger bi) {
                return bi.longValueExact();
            }
            if (number instanceof Long l) {
                return l;
            }
            if (number instanceof Integer i) {
                return i;
            }
            if (number instanceof Short s) {
                return s;
            }
            if (number instanceof Byte b) {
                return b;
            }
            // Double/Float/прочие: через точное десятичное представление —
            // longValueExact() отказывает и на выходе за диапазон, и на дроби
            // (дроби сюда не доходят — только isIntegral(), но guard полный).
            // Сюда попадают и целозначные double вне диапазона (насыщение
            // longValue() вместо отказа — та же тихая порча, что у BigDecimal).
            return new java.math.BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException e) {
            throw new com.zorrodev.bpm.contract.exception.EngineException(
                "Variable '" + name + "' value " + number + " is an integer outside LONG range"
                    + " [" + Long.MIN_VALUE + ", " + Long.MAX_VALUE + "]"
                    + " — refusing to store a truncated value", e);
        }
    }

    public boolean isIntegral(Number number) {
        // WO-ENG-25 (NEW-05): любой BigDecimal-подобный — через scale, не
        // через double. Нормализация здесь же, чтобы прямые вызывающие (не
        // только toProcessVariable) получали тот же контракт: scala.BigDecimal
        // ≥2⁵³ с дробью через double теряет дробную часть (rint == d) и
        // ложно считается целым.
        Number normalized = number instanceof scala.math.BigDecimal sbd ? sbd.bigDecimal() : number;
        if (normalized instanceof Long || normalized instanceof Integer || normalized instanceof Short || normalized instanceof Byte) {
            return true;
        }
        if (normalized instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0;
        }
        double d = normalized.doubleValue();
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    public boolean isStructuredResult(Object v) {
        return v instanceof java.util.Map || v instanceof java.util.List
            || v instanceof scala.collection.Map || v instanceof scala.collection.Iterable;
    }

    /**
     * WO-C8-32: appends {@code value} to the root JSON-list variable {@code name}
     * (creating it when absent). Generalised from the multi-instance output-collection
     * append (moved here verbatim so MI and ad-hoc share one mechanism instead of two).
     * <p>
     * WO-REL-41 (B-8, п.1): the read-modify-write now happens in ONE SQL statement
     * ({@code DBService.appendJsonElement}) — the old root-scoped read, Java-side
     * merge and root-scoped write lost elements when parallel MI branches (or
     * ad-hoc flows) completed concurrently.
     */
    public void appendToJsonList(UUID processInstanceId, String name, Object value) {
        dbService.appendJsonElement(processInstanceId, name,
            objectMapper.writeValueAsString(toJavaStructure(value)));
    }

    // ─── Boundary helpers ────────────────────────────────────────────

    public Instant computeDueAt(BpmnElementModel element) {
        return computeDueAt(Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null), element.getId());
    }

    public Instant computeDueAt(com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel timer, String elementId) {
        return computeDueAtFallback(timer, elementId);
    }

    /**
     * Computes the due-at instant for a timer, resolving FEEL expressions ({@code =expr}) against
     * process instance variables before parsing.  Literal ISO-8601 values (no leading {@code =}) use
     * the original parsing path.
     *
     * @param element          the BPMN element with a timer extension
     * @param processInstanceId the process instance whose variables are available to FEEL
     * @return the computed {@link Instant} when the timer should fire
     * @throws com.zorrodev.bpm.contract.exception.EngineException if the expression is null,
     *         the FEEL evaluation returns null, or the result cannot be converted to the expected type
     */
    public Instant computeDueAt(BpmnElementModel element, UUID processInstanceId) {
        return computeDueAt(Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null), element.getId(), processInstanceId);
    }

    /**
     * Computes the due-at instant for a timer, resolving FEEL expressions ({@code =expr}) against
     * process instance variables before parsing.
     */
    public Instant computeDueAt(com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel timer,
                                String elementId, UUID processInstanceId) {
        if (timer == null || timer.getType() == null || timer.getExpression() == null) {
            throw new com.zorrodev.bpm.contract.exception.EngineException("Timer event " + elementId + " has no timer definition");
        }
        String expression = timer.getExpression();
        if (expression.startsWith("=")) {
            String feelExpr = expression.substring(1);
            Object value = scriptService.evaluateExpression(feelExpr, dbService.getVariables(processInstanceId));
            if (value == null) {
                throw new com.zorrodev.bpm.contract.exception.EngineException(
                    "Timer event " + elementId + ": FEEL expression '" + expression + "' returned null");
            }
            return switch (timer.getType()) {
                case DURATION -> {
                    if (value instanceof Duration d) {
                        yield Instant.now().plus(d);
                    }
                    try {
                        yield Instant.now().plus(Duration.parse(value.toString()));
                    } catch (Exception e) {
                        throw new com.zorrodev.bpm.contract.exception.EngineException(
                            "Timer event " + elementId + ": FEEL expression '" + expression + "' did not resolve to a valid duration (got: " + value + ")", e);
                    }
                }
                case DATE -> {
                    if (value instanceof Instant i) {
                        yield i;
                    }
                    if (value instanceof java.time.OffsetDateTime odt) {
                        // WO-ENG-4: explicit offset → use it
                        yield odt.toInstant();
                    }
                    if (value instanceof java.time.ZonedDateTime zdt) {
                        // WO-ENG-4: explicit zone → use it
                        yield zdt.toInstant();
                    }
                    if (value instanceof java.time.LocalDateTime ldt) {
                        // WO-ENG-4: zone-naive → interpret in businessZone, not UTC
                        yield ldt.atZone(businessZone).toInstant();
                    }
                    if (value instanceof java.time.LocalDate ld) {
                        // WO-ENG-4: zone-naive → interpret in businessZone, not UTC
                        yield ld.atStartOfDay(businessZone).toInstant();
                    }
                    if (value instanceof java.util.Date d) {
                        yield d.toInstant();
                    }
                    try {
                        yield Instant.parse(value.toString());
                    } catch (Exception e) {
                        throw new com.zorrodev.bpm.contract.exception.EngineException(
                            "Timer event " + elementId + ": FEEL expression '" + expression + "' did not resolve to a valid date/instant (got: " + value + ")", e);
                    }
                }
                case CYCLE -> {
                    try {
                        // WO-ENG-4: use businessZone for cycle/cron zone resolution
                        yield com.zorrodev.bpm.engine.scheduler.TimerExpressions.firstOccurrence(value.toString(), Instant.now(), businessZone);
                    } catch (Exception e) {
                        throw new com.zorrodev.bpm.contract.exception.EngineException(
                            "Timer event " + elementId + ": FEEL expression '" + expression + "' did not resolve to a valid cycle expression (got: " + value + ")", e);
                    }
                }
            };
        }
        // Non-FEEL expression: use the original literal parsing path
        return computeDueAtFallback(timer, elementId);
    }

    /** Original literal-only parsing path, kept for backward compatibility and as the non-FEEL fallback. */
    private Instant computeDueAtFallback(com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel timer, String elementId) {
        if (timer == null || timer.getType() == null || timer.getExpression() == null) {
            throw new com.zorrodev.bpm.contract.exception.EngineException("Timer event " + elementId + " has no timer definition");
        }
        return switch (timer.getType()) {
            case DURATION -> Instant.now().plus(Duration.parse(timer.getExpression()));
            case DATE -> Instant.parse(timer.getExpression());
            case CYCLE -> com.zorrodev.bpm.engine.scheduler.TimerExpressions.firstOccurrence(timer.getExpression(), Instant.now(), businessZone);
        };
    }

    public String evaluateCorrelationKey(BpmnElementModel element, UUID processInstanceId) {
        String expression = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMessageEventExtension)
            .map(MessageEventExtensionModel::getCorrelationKeyExpression)
            .filter(s -> !s.isBlank())
            .orElse(null);
        if (expression == null) {
            return null;
        }
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        Object value = scriptService.evaluateExpression(expression, dbService.getVariables(processInstanceId));
        return value == null ? null : value.toString();
    }

    public Object toJavaStructure(Object v) {
        if (v instanceof scala.collection.Map<?, ?> sm) {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            scala.collection.Iterator<?> it = sm.iterator();
            while (it.hasNext()) {
                scala.Tuple2<?, ?> entry = (scala.Tuple2<?, ?>) it.next();
                out.put(String.valueOf(entry._1()), toJavaStructure(entry._2()));
            }
            return out;
        }
        if (v instanceof scala.collection.Iterable<?> si) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            scala.collection.Iterator<?> it = si.iterator();
            while (it.hasNext()) {
                out.add(toJavaStructure(it.next()));
            }
            return out;
        }
        if (v instanceof java.util.Map<?, ?> jm) {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
            jm.forEach((k, val) -> out.put(String.valueOf(k), toJavaStructure(val)));
            return out;
        }
        if (v instanceof java.util.List<?> jl) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            for (Object e : jl) {
                out.add(toJavaStructure(e));
            }
            return out;
        }
        return v;
    }

    // ── WO-C8-34 (CR-06): compensation thrower bookkeeping ────────────────────
    //
    // These used to exist TWICE — once in CompensationThrowHandler (throw side) and
    // once in CompletionService (resume side) — and the copies had already DIVERGED:
    // the throw side snapshotted its candidate list, the resume side recomputed it, so
    // a target that completed AFTER the throw (handler never launched) kept the parked
    // thrower waiting forever (security review B1). One implementation, both callers.

    /**
     * Candidates one compensation thrower owns: scope-confined completed activities
     * (the CancelEndHandler pattern) plus the pre-existing {@code activityRef} filter.
     * {@code tokenId} is the thrower's own token — its {@code scopeActivityId} decides
     * the scope.
     */
    public List<Activity> compensationTargets(UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement) {
        String activityRef = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel::getReference)
            .orElse(null);
        Token throwToken = dbService.findToken(tokenId).orElse(null);
        UUID scopeActivityId = throwToken == null ? null : throwToken.getScopeActivityId();
        List<Activity> targets = dbService.getCompletedActivities(processInstanceId);
        if (scopeActivityId != null) {
            targets = filterActivitiesInScope(processInstanceId, targets, scopeActivityId);
        }
        if (activityRef != null) {
            String ref = activityRef;
            targets = targets.stream().filter(a -> ref.equals(a.getBpmnElementId())).toList();
        }
        return targets;
    }

    public boolean compensationThrowerHasPending(UUID processInstanceId, UUID throwActivityId,
            BpmnProcessDefinitionModel bpmn, List<Activity> targets) {
        return compensationThrowerHasPending(processInstanceId, throwActivityId, bpmn, targets, java.util.Set.of());
    }

    /**
     * WO-C8-34 (CR-06, security review B1): does {@code throwActivityId} still wait for a
     * compensation handler?
     *
     * <p>The throw side snapshotted its candidate list; a resume that recomputes the list
     * sees MORE completed activities than the throw did (anything finished afterwards),
     * and such a target's handler was never launched — so "no row for the handler" read
     * as "pending" and the thrower parked forever. The thrower's own row creation time IS
     * the snapshot: a target belongs to this throw iff it completed at or before the
     * throw. The tie (same millisecond) fails closed — the row is kept, we still wait.
     *
     * @param forceTerminalHandlerIds handler elements the CALLER has just declared
     *        finished-with-failure (the retry-exhausted path). Their row may still read
     *        CREATED/IN_PROGRESS inside the same transaction — {@code errorActivity} is a
     *        bulk UPDATE that the repeatable-read snapshot of the following query does not
     *        see (the same hole WO-ENG-23 hit in {@code IncidentService}). The failing
     *        path is the authority on its own outcome, so it passes the verdict in rather
     *        than hoping the snapshot agrees.
     */
    public boolean compensationThrowerHasPending(UUID processInstanceId, UUID throwActivityId,
            BpmnProcessDefinitionModel bpmn, List<Activity> targets,
            java.util.Set<String> forceTerminalHandlerIds) {
        Activity throwerRow = throwActivityId == null ? null : dbService.getActivity(throwActivityId);
        Instant thrownAt = throwerRow == null ? null : throwerRow.getCreatedAt();
        List<Activity> owned = targets;
        if (thrownAt != null) {
            final Instant cut = thrownAt;
            owned = targets.stream()
                .filter(a -> a.getCompletedAt() == null || !a.getCompletedAt().isAfter(cut))
                .toList();
        }
        Map<String, BpmnElementModel> boundaryIndex = compensationBoundaryIndex(bpmn);
        for (Activity target : owned) {
            String handlerId = compensationHandlerId(boundaryIndex, bpmn, target.getBpmnElementId());
            if (handlerId == null || forceTerminalHandlerIds.contains(handlerId)) {
                continue;
            }
            if (isCompensationHandlerPending(processInstanceId,
                    throwerRow == null ? null : throwerRow.getToken(), handlerId)) {
                return true;
            }
        }
        return false;
    }

    /** host-id → its compensation boundary event (one pass over the model). */
    public Map<String, BpmnElementModel> compensationBoundaryIndex(BpmnProcessDefinitionModel bpmn) {
        Map<String, BpmnElementModel> index = new HashMap<>();
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.COMPENSATION_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (attached != null) {
                index.putIfAbsent(attached, element);
            }
        }
        return index;
    }

    /** handler element id for a compensated host, or null when it has none. */
    public String compensationHandlerId(Map<String, BpmnElementModel> boundaryIndex,
            BpmnProcessDefinitionModel bpmn, String hostElementId) {
        BpmnElementModel boundary = boundaryIndex.get(hostElementId);
        if (boundary == null) {
            return null;
        }
        String handlerId = Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getBoundaryEventExtension)
            .map(com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel::getCompensationHandlerId)
            .orElse(null);
        return handlerId == null || bpmn.getElement(handlerId) == null ? null : handlerId;
    }

    /**
     * Latest-row-wins for one compensation handler element. Pending unless a COMPLETED
     * row exists AND no row is newer than it.
     *
     * <p>WO-C8-34 security review B2: a handler whose retries ran out is ERROR + incident. That
     * IS the outcome the operator sees, so the waiting thrower must not hang on it (nor on
     * a handler cancelled by an unrelated interrupting boundary). Those rows are in
     * neither instance-wide list ({@code getActiveActivities} = CREATED/IN_PROGRESS,
     * {@code getCompletedActivities} = COMPLETED), so they are read with one token-scoped
     * query — handlers run on the thrower's own token ({@code runCompensation} passes it
     * to {@code executor.execute}). A null token finds no terminal rows and fails closed.
     */
    public boolean isCompensationHandlerPending(UUID processInstanceId, UUID tokenId, String handlerId) {
        Instant newestCompleted = null;
        UUID newestCompletedId = null;
        Instant newestActive = null;
        UUID newestActiveId = null;
        Instant newestFailed = null;
        UUID newestFailedId = null;
        for (Activity a : dbService.getCompletedActivities(processInstanceId)) {
            if (handlerId.equals(a.getBpmnElementId())
                && (newestCompleted == null || compareActivityRecency(a.getCompletedAt(), a.getId(),
                    newestCompleted, newestCompletedId) > 0)) {
                newestCompleted = a.getCompletedAt();
                newestCompletedId = a.getId();
            }
        }
        if (tokenId != null) {
            for (Activity a : dbService.getActivitiesByTokenAndBpmnElementId(tokenId, handlerId)) {
                if (a.getStatus() != ActivityStatus.ERROR && a.getStatus() != ActivityStatus.CANCELLED) {
                    continue;
                }
                if (newestFailed == null || compareActivityRecency(a.getCompletedAt(), a.getId(),
                    newestFailed, newestFailedId) > 0) {
                    newestFailed = a.getCompletedAt();
                    newestFailedId = a.getId();
                }
            }
        }
        for (Activity a : dbService.getActiveActivities(processInstanceId)) {
            if (handlerId.equals(a.getBpmnElementId())
                && (newestActive == null || compareActivityRecency(a.getCreatedAt(), a.getId(),
                    newestActive, newestActiveId) > 0)) {
                newestActive = a.getCreatedAt();
                newestActiveId = a.getId();
            }
        }
        if (newestFailed != null
            && (newestCompleted == null || compareActivityRecency(newestFailed, newestFailedId,
                newestCompleted, newestCompletedId) > 0)
            && (newestActive == null || compareActivityRecency(newestFailed, newestFailedId,
                newestActive, newestActiveId) > 0)) {
            return false; // newest word on this handler is "failed/cancelled" — waiting is over
        }
        if (newestCompleted == null) {
            return true; // launched (boundary maps it) but never finished — still pending
        }
        if (newestActive == null) {
            return false;
        }
        return compareActivityRecency(newestActive, newestActiveId, newestCompleted, newestCompletedId) > 0;
    }

    /** Timestamp-then-id ordering; a null timestamp sorts as the older one. */
    public static int compareActivityRecency(Instant t1, UUID id1, Instant t2, UUID id2) {
        if (t1 != null && t2 != null) {
            int c = t1.compareTo(t2);
            if (c != 0) {
                return c;
            }
        } else if (t1 != null) {
            return 1;
        } else if (t2 != null) {
            return -1;
        }
        if (id1 != null && id2 != null) {
            return id1.compareTo(id2);
        }
        return 0;
    }

    // ── WO-C8-35 (CR-09) раунд 3: единое правило готовности inclusive-join ──────────
    //
    // Два join-механизма жили по разным правилам: parallel join сверяет приходы со своими
    // СОБСТВЕННЫми входящими, inclusive join — со статическим счётчиком `expected`, который
    // писал только inclusive-сплит. От XOR/параллельного форка/неявной AND-вилки/выхода из
    // подпроцесса счётчика нет → join логировал «не готов» на КАЖДЫЙ приход и ждал вечно,
    // молча (без инцидента).
    //
    // Раунд 3 (HOLD красного флага, MAJOR-1/MAJOR-2) счётчик снят ЦЕЛИКОМ: он писался
    // вслепую (UK-нарушение на двух сплитах в один join) и уезжал на шлюз, достигаемый
    // 2 из 3 ветвей. Теперь правило одно для ВСЕХ inclusive-join и целиком выводится из
    // состояния инстанса:
    //
//     ready(J) ⟺ никто в инстансе НЕ МОЖЕТ доставить в J ещё одну ветвь
    //
    // «кто-то может» — объединение ДВУХ множеств (раунд 5: было три, см. ниже):
    //   (a) активные activity                   — обычная живая ветвь, и вместе с ней
    //                                               ВСЕ граничные события, привязанные к её
    //                                               элементу (ребро границы внутри canReach);
    //   (b) припаркованные шлюзы (ШАГ 1/3)      — ветвь, дошедшая до join'а и ждущая, тоже
    //                                               доставка в полёте; плюс сам сплит в фазе
    //                                               разветвления (его activity остаётся живой
    //                                               весь фанаут — см. InclusiveGatewayHandler).
    //
    // Раунд 5: множество «взведённых триггеров» осталось, но перестало быть САМОСТОЯТЕЛЬНЫМ
    // правилом. Раньше оно и было вселенной — и потому не видело CONDITIONAL/ERROR/ESCALATION-границ
    // (строки armed у них нет никогда): join срабатывал на первом приходе, а по границе ВТОРЫМ
    // (BLOCKER-3 security review, живой прогон). Теперь structural-ребро границы проходится для ЛЮБОГО типа,
    // а armed-набор используется ровно там, где он единственный источник знания: «эта граница уже
    // сработала, второй выстрел невозможен» (timer/message/signal). На определениях без таких
    // границ запросов не делается вовсе.
    //
    // Намеренная асимметрия: консервативность (b) может заставить join ЖДАТ лишнего, но
    // никогда не срабатывает раньше времени. Обратную сторону — «последний доставщик умер» —
    // снимает перепроверка припаркованных join'ов при каждой деактивации (ШАГ 3, B2), иначе
    // консервативность была бы вечным ожиданием (BLOCKER-2).

    /**
     * Can a live execution sitting on {@code fromElementId} still reach
     * {@code targetElementId}? Forward BFS, {@code visited}-guarded, so it terminates on cyclic
     * graphs.
     *
     * <p>Reachability goes through TWO edges out of every visited element (WO-C8-35 раунд 5,
     * BLOCKER-3 security review):
     * <ol>
     *   <li>исходящие SEQUENCE-потоки элемента;</li>
     *   <li>исходящие потоки ГРАНИЧНЫХ событий, привязанных к нему
     *       ({@code BpmnProcessDefinitionModel.getBoundaryEventsAttachedTo}) — любого типа,
     *       прерывающих и нет.</li>
     * </ol>
     * Второе ребро обязано быть здесь, а не в отдельном «взведённом» множестве: граница привязана
     * к хосту через {@code attachedToRef}, а не потоком, и наличие строки armed есть только у
     * timer/message/signal — у CONDITIONAL/ERROR/ESCALATION границы её нет ВООБЩЕ. Пока хост жив,
     * граница может выстрелить, значит хост — возможный доставщик ветви; хост умер → хост исчез из
     * множества живых activity, и правило перечитывается на деактивации (перепроверка
     * припаркованных join'ов).
     *
     * <p>Conditions on flows out of a gateway are NOT evaluated: a path through a conditional
     * gateway counts as reachable even when its condition may evaluate false. That is
     * deliberately conservative — the join waits rather than fires early (the opposite failure
     * duplicates business side effects, which is what the static counter used to cause).
     * The cost of that conservatism is a possible over-wait, and it is paid back by
     * {@code InclusiveGatewayHandler.resumeParkedInclusiveJoins}: when the last possible
     * deliverer dies (XOR took its other branch, cancellation, termination, a boundary fired,
     * an error/escalation boundary killed its host), every parked inclusive join is re-evaluated.
     * Counting a false-condition path as reachable IS the wait-forever defect if nothing ever
     * re-checks — that was BLOCKER-2, and this Javadoc previously asserted the exact opposite
     * of what the code did.
     */
    public boolean canReach(BpmnProcessDefinitionModel bpmn, String fromElementId, String targetElementId) {
        return canReachWithLiveBoundaries(bpmn, fromElementId, targetElementId, null);
    }

    /**
     * То же обход, но ребро границы проходится только для границ, которые ещё МОГУТ выстрелить.
     *
     * <p><b>Раунд 6 (BLOCKER-6): критерий ИНВЕРТИРОВАН — «исключать отработавшие», а не «включать
     * взведённые» (решение maintainer).</b> Прежний гейт по {@code armedBoundaryElementIds} отделял
     * «граница снята» от «граница ещё не взведена», а эти состояния в нём неразличимы: у границы
     * на хосте, который ЕЩЁ НЕ СТАРТОВАЛ, строки job'а нет вовсе, и ребро молча пропадало — join
     * срабатывал раньше времени, а по границе срабатывал ВТОРЫМ (живой прогон security review, диаграмма
     * «таймаут-ветка + основная ветка в общий join»). Теперь отсеиваются только границы из
     * {@code exhaustedBoundaryElementIds} — те, доставить которым уже нечем.
     *
     * <p>Что значит «отработала» (см. {@code DBService.getExhaustedBoundaryElementIds}): хост умер
     * (все его activity-строки терминальные — тогда снимается ЛЮБАЯ граница, хоть у неё и нет
     * персистентной записи) либо одноразовый граничный таймер выстрелил и не перевзведён. У
     * message/signal/conditional/error/escalation-границ, которые могут срабатывать повторно, пока
     * хост жив, второго состояния нет вовсе — их снимает только смерть хоста, а до неё они считаются
     * достижимыми (консервативно: join подождёт, потом перепроверка по хуку).
     *
     * @param exhaustedBoundaryElementIds element id границ, которые доставить ветвь уже не могут;
     *                                   {@code null}/пусто — «все границы этого определения ещё
     *                                   могут выстрелить» (частый случай: запросов не было вовсе)
     */
    public boolean canReachWithLiveBoundaries(BpmnProcessDefinitionModel bpmn, String fromElementId,
            String targetElementId, java.util.Set<String> exhaustedBoundaryElementIds) {
        if (fromElementId == null || targetElementId == null || fromElementId.equals(targetElementId)) {
            return fromElementId != null && fromElementId.equals(targetElementId);
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(fromElementId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            if (current.equals(targetElementId)) {
                return true;
            }
            BpmnElementModel element = bpmn.getElement(current);
            if (element == null) {
                continue;
            }
            enqueueOutgoingTargets(bpmn, element.getOutgoing(), queue);
            // Ребро границы: у хоста может быть граница, у которой НЕТ строки activity, но есть
            // исходящий поток в сторону join'а. Хост, который ещё не стартовал, в это множество
            // попадает по графу (taskSvc → taskX) — и обязан попасть, иначе join сработает до
            // срока (BLOCKER-6).
            for (BpmnElementModel boundary : bpmn.getBoundaryEventsAttachedTo(current)) {
                if (exhaustedBoundaryElementIds != null
                        && exhaustedBoundaryElementIds.contains(boundary.getId())) {
                    // хост умер либо одноразовый таймер отстрелял: ветвь эта уже доставлена
                    // (или будет доставлена отменой хоста), повторного выстрела не будет.
                    continue;
                }
                enqueueOutgoingTargets(bpmn, boundary.getOutgoing(), queue);
            }
        }
        return false;
    }

    private void enqueueOutgoingTargets(BpmnProcessDefinitionModel bpmn, List<String> outgoingFlowIds,
            Deque<String> queue) {
        if (outgoingFlowIds == null) {
            return;
        }
        for (String flowId : outgoingFlowIds) {
            BpmnFlowModel flow = bpmn.getFlow(flowId);
            if (flow != null && flow.getTargetRef() != null) {
                queue.add(flow.getTargetRef());
            }
        }
    }

    /**
     * Is some OTHER still-live execution in this instance able to reach the join?
     *
     * <p>Arrived branches are already COMPLETED by the time the join runs
     * ({@code FlowNavigator.processFlow} completes the flow row), so they do not count as live —
     * the arriving branch excludes itself without special-casing. An inclusive SPLIT that is
     * currently dispatching (its activity row stays live for the whole fan-out, see
     * {@code InclusiveGatewayHandler}) IS counted here: that is what keeps the join from firing
     * on the first arrival of a pass-through fan-out, whose sibling branches have no activity
     * row yet.
     */
    public boolean hasOtherLiveExecutionReaching(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel join) {
        return hasOtherLiveExecutionReaching(processInstanceId, bpmn, join,
            exhaustedBoundaryIds(processInstanceId, bpmn));
    }

    /**
     * Раунд 6 (BLOCKER-6): {@code exhaustedBoundaryElementIds} — element id границ инстанса,
     * доставить которые уже НЕЧЕГО (хост умер либо одноразовый таймер отстрелял). Если в
     * определении нет НИ ОДНОЙ границы, запросов не делается вовсе (обычный случай).
     */
    public boolean hasOtherLiveExecutionReaching(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel join, java.util.Set<String> exhaustedBoundaryElementIds) {
        for (Activity activity : dbService.getActiveActivities(processInstanceId)) {
            String elementId = activity.getBpmnElementId();
            if (elementId == null || elementId.equals(join.getId())) {
                continue;
            }
            if (canReachWithLiveBoundaries(bpmn, elementId, join.getId(), exhaustedBoundaryElementIds)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Раунд 6: границы, доставить которые уже нечем. Лениво и только если в определении есть
     * границы вообще (мемо в модели) — у большинства определений правило не делает ни одного
     * запроса.
     */
    private java.util.Set<String> exhaustedBoundaryIds(UUID processInstanceId, BpmnProcessDefinitionModel bpmn) {
        if (!bpmn.hasBoundaryEvent()) {
            return java.util.Set.of();
        }
        return dbService.getExhaustedBoundaryElementIds(processInstanceId,
            bpmn.getBoundaryHostByBoundaryElementId());
    }

    /**
     * WO-C8-35 (CR-09, ШАГ 1/3): a branch that already arrived at ANOTHER gateway and is parked
     * there is a delivery in flight too — it holds open arrival rows, and once that gateway
     * fires it continues downstream, possibly into this join. Without this the join could fire
     * while an upstream join was still holding a branch destined for it (MAJOR-1's partial-merge
     * shape: {@code j1} parks with A/B while {@code j2} waits on j1's outgoing).
     */
    public boolean hasParkedJoinReaching(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel join) {
        for (String gatewayId : dbService.getGatewaysWithOpenArrivals(processInstanceId)) {
            if (gatewayId.equals(join.getId())) {
                continue;
            }
            if (canReach(bpmn, gatewayId, join.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * WO-C8-35 (CR-09) раунд 3: THE single readiness rule for every inclusive join, whatever
     * split it comes from. See the block comment above for the two sets that make up
     * "can still deliver".
     *
     * <p>Раунд 5 (BLOCKER-3): множество «взведённых триггеров» УДАЛЕНО целиком вместе с тремя
     * запросами и индексом под них. Причина не в том, что оно было лишним, а в том, что оно было
     * НЕПОЛНЫМ: строка armed есть только у timer/message/signal, а условная и escalation-границы
     * её не имеют никогда, поэтому join, в который могла доставить такая граница, считал
     * инстанс пустым и срабатывал на первом приходе — а по границе срабатывал ВТОРЫМ
     * (живой прогон security review BLOCKER-3). Теперь то же свойство получено СТРУКТУРНО, из
     * {@link #canReach}: живой хост + его границы = возможный доставщик, без единого запроса.
     */
    public boolean isInclusiveJoinReady(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel join) {
        return !hasOtherLiveExecutionReaching(processInstanceId, bpmn, join,
                exhaustedBoundaryIds(processInstanceId, bpmn))
            && !hasParkedJoinReaching(processInstanceId, bpmn, join);
    }

}
