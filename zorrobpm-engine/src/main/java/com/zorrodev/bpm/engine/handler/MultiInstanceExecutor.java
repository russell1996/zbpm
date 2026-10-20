package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-instance collaborator extracted from ActivityServiceImpl (WO-AUD-16).
 * Contains all multi-instance logic: entering, spawning, continuing, and aggregating.
 * Navigation via FlowNavigator; dispatch via TokenExecutor (passed as parameter, not injected).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiInstanceExecutor {

    private final DBService dbService;
    private final ScriptService scriptService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final BpmnService bpmnService;
    private final ElementSupport elementSupport;
    private final BoundaryScheduler boundaryScheduler;
    private final FlowNavigator flowNavigator;
    // WO-C8-40: MI-спaун инстансов подпроцесса — через ObjectProvider, а не прямую
    // инъекцию: SubProcessHandler уже зависит от MultiInstanceExecutor (MI-вход),
    // прямая пара дала бы Spring circular dependency (прецедент — HandlerRegistry
    // резолвит хендлеры лениво по той же причине). ПОРЯДОК ПОЛЕЙ ЗАМОРОЖЕН:
    // provider — ПОСЛЕДНИМ, иначе ломаются ручные new в юнит-тестах (P-24: один
    // конструктор Lombok на всех вызывающих).
    private final org.springframework.beans.factory.ObjectProvider<SubProcessHandler> subProcessHandlerProvider;

    /**
     * WO-REL-31 F23: upper bound on how many MI instances a single multi-instance entry may
     * spawn synchronously in one transaction. Justification: {@code MAX_MI_CARDINALITY = 1_000}
     * is 2× the CR-3 fan-out batch size (500) and already a heavy single-transaction operation
     * (~1 000 activities + ~1 000 user/service-task rows + ~2 000 variable rows + boundary
     * subscriptions ≈ 5-6k rows). Anything above is unbounded synchronous work in one
     * transaction — a process model asking for it should use a different pattern (nested
     * multi-instance, message-driven chunks), not a giant spawn. The audit flagged the same
     * OOM risk on the 10k-subscriber fan-out; this is the intra-instance twin of that bound.
     */
    static final int MAX_MI_CARDINALITY = 1_000;

    /**
     * WO-PERF-9 (B-8, full-scan): идентификаторы, на которые может ссылаться
     * FEEL-выражение — консервативный SUPERSET (см. {@link FeelVariableNames}).
     * Оставлен как делегирующий алиас: на него ссылаются тесты WO-PERF-9
     * ({@code MultiInstanceExecutorPinpointTest}); новая логика — в общем классе.
     */
    static Set<String> extractFeelVariableNames(String expression) {
        return FeelVariableNames.extract(expression);
    }

    /**
     * WO-PERF-9: root variables needed to evaluate the given FEEL
     * expressions — one indexed SELECT for exactly the referenced names
     * instead of {@code getVariables(pi)} over the whole instance scope.
     */
    private List<ProcessVariable> rootVariablesFor(UUID processInstanceId, String... expressions) {
        Set<String> names = new LinkedHashSet<>();
        for (String e : expressions) {
            names.addAll(extractFeelVariableNames(e));
        }
        return dbService.getVariablesByNames(processInstanceId, names);
    }

    /**
     * WO-PERF-9: scoped counterpart of {@link #rootVariablesFor} — the
     * root+scope merged view restricted to the referenced names ("scoped
     * wins" preserved by the query layer).
     */
    private List<ProcessVariable> scopedVariablesFor(UUID processInstanceId, UUID scopeId, String... expressions) {
        Set<String> names = new LinkedHashSet<>();
        for (String e : expressions) {
            names.addAll(extractFeelVariableNames(e));
        }
        return dbService.getScopedVariablesByNames(processInstanceId, scopeId, names);
    }

    /**
     * WO-C8-40: chain-контекст токена для MI-входа вложенных элементов —
     * root + ВСЯ цепочка внешних скоупов токена (nearest-wins), pinpoint по
     * именам выражений. Элемент верхнего уровня (токен без scope-цепочки)
     * получает ровно root — поведение побайтово то же, что rootVariablesFor.
     * Нужно MI внутри (MI-)подпроцессов: их inputCollection ссылается на
     * цикл-переменную внешнего инстанса, которой в root нет.
     *
     * <p>WO-C8-42: слои цепочки — scope-ONLY (root идёт первым слоем как
     * fallback): merge-ридер подмешивал stale root в каждый слой и перетирал
     * байнды внешних скоупов. При чистом root множество побайтово то же.
     */
    private List<ProcessVariable> chainVariablesFor(UUID processInstanceId, UUID token, String... expressions) {
        Set<String> names = new LinkedHashSet<>();
        for (String e : expressions) {
            names.addAll(extractFeelVariableNames(e));
        }
        List<UUID> chain = scopeChainOfToken(processInstanceId, token);
        if (chain.isEmpty()) {
            return dbService.getVariablesByNames(processInstanceId, names);
        }
        java.util.Map<String, ProcessVariable> merged = new java.util.LinkedHashMap<>();
        for (ProcessVariable v : dbService.getVariablesByNames(processInstanceId, names)) {
            merged.put(v.getName(), v);
        }
        for (UUID scopeId : chain) {
            for (ProcessVariable v : dbService.getScopeOnlyVariablesByNames(processInstanceId, scopeId, names)) {
                merged.put(v.getName(), v);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * WO-C8-40: scope-цепочка токена (outermost-first): от корня через
     * scopeActivityId-звенья до непосредственного scope токена. Тот же обход,
     * что {@code ElementSupport.enclosingScopeChain}, но от токена, а не от
     * activity — у MI-входа строки инстанса ещё нет.
     */
    private List<UUID> scopeChainOfToken(UUID processInstanceId, UUID tokenId) {
        List<UUID> chain = new ArrayList<>();
        Optional<com.zorrodev.bpm.engine.dto.Token> token = dbService.findToken(tokenId);
        while (token.isPresent()) {
            UUID scopeId = token.get().getScopeActivityId();
            if (scopeId != null && !chain.contains(scopeId)) {
                chain.add(scopeId);
            }
            UUID parentId = token.get().getParentId();
            token = parentId == null ? Optional.empty() : dbService.findToken(parentId);
        }
        java.util.Collections.reverse(chain);
        return chain;
    }

    public boolean isMultiInstance(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .isPresent();
    }

    /**
     * Multi-instance user task. Parallel: spawns all N user-task activities on the same token at once.
     * Sequential: spawns only the first instance; the next is created as each completes (see
     * {@link #multiInstanceContinue}). N comes from {@code loopCardinality}; the join is told to expect N
     * (reusing the parallel/inclusive arrival mechanism).
     */
    public void enter(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement, TokenExecutor executor) {
        MultiInstanceExtensionModel mi = bpmnElement.getExtensions().getMultiInstanceExtension();
        int count;
        Object collection;
        try {
            // WO-REL-41 (B-8, п.3): ONE root read and ONE inputCollection FEEL
            // eval per entry — shared by the cardinality resolution below and
            // the spawn that follows, instead of each fetching/evaluating them
            // again on the same transition.
            // WO-PERF-9 (B-8, full-scan): the read is pinpoint — only the
            // variables the inputCollection/loopCardinality expressions can
            // reference, not the whole instance scope.
            // WO-C8-40: контекст — chain токена (вложенный MI видит внешние
            // скоупы); сверху цепочка пуста и это ровно rootVariablesFor.
            List<ProcessVariable> variables =
                chainVariablesFor(processInstanceId, token, mi.getInputCollection(), mi.getCardinality());
            collection = null;
            if (mi.getInputCollection() != null && !mi.getInputCollection().isBlank()) {
                collection = scriptService.evaluateExpression(mi.getInputCollection(), variables);
            }
            count = resolveCardinality(bpmnElement, variables, collection);
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException e) {
            // WO-ENG-24: временная перегрузка пула — не ошибка модели.
            // Проброс до 503-хендлера (как главный путь ActivityService),
            // а не инцидент: неверные выражения по-прежнему идут в инцидент
            // ниже, семантика WO-REL-31 F23 для них не меняется ни на бит.
            throw e;
        } catch (EngineException e) {
            // WO-REL-31 F23: a failed cardinality resolution (fractional/out-of-int-range value,
            // missing characteristics) is an element failure — raise a visible incident on the
            // element, never abort the whole request: EngineException would otherwise propagate
            // through ActivityServiceImpl.execute as an abort.
            raiseCardinalityIncident(processInstanceId, token, bpmnElement, e.getMessage());
            return;
        }
        if (count == 0) {
            log.info("{}/{}: Multi-instance {} has zero instances, skipping", processInstanceId, token, bpmnElement.getId());
            // WO-DIFF-3 (#6): Zeebe/Raxon still create the output variable as
            // an empty array when the collection is empty — an absent variable
            // is not the same as an empty one for the external observer.
            writeEmptyOutputCollection(processInstanceId, mi);
            flowNavigator.proceedToOutgoing(processInstanceId, token, bpmnElement.getProcessDefinition(), bpmnElement, executor);
            return;
        }
        if (count < 0) {
            // F23: negative is invalid (the old intValue() path only reached count <= 0 here and
            // silently skipped — including for wrapped overflow values; the skip is reserved for 0).
            raiseCardinalityIncident(processInstanceId, token, bpmnElement,
                "Multi-instance " + bpmnElement.getId() + " cardinality must be non-negative: " + count);
            return;
        }
        if (count > MAX_MI_CARDINALITY) {
            // F23: cap synchronous spawn (covers loopCardinality AND inputCollection paths).
            raiseCardinalityIncident(processInstanceId, token, bpmnElement,
                "Multi-instance " + bpmnElement.getId() + " cardinality " + count
                    + " exceeds the engine limit of " + MAX_MI_CARDINALITY);
            return;
        }
        String miId = bpmnElement.getId();
        // WO-ENG-7: unique batch UUID per MI entry isolates join-bookkeeping between loop iterations.
        // Without this, every iteration reuses the same (processInstanceId, miId) key and data from
        // different iterations can collide (=done=true fires too early — the prod bug 52e6b64c).
        // WO-C8-40: batch пишется в scope токена входа (вложенный MI — в scope
        // внешнего инстанса, верхний — в root): два внешних инстанса делят miId,
        // но НЕ scope — их join-ключи не коллидируют. Resolve — там же.
        // WO-C8-41 (F2, oracle S1-1/S4): предсоздание outputCollection при входе —
        // [null x N] (S4: N=0 даёт []), в CONTAINING scope (innermost scope токена
        // входа: root сверху, outer-instance scope у вложенного). Zeebe показывает
        // предсозданный массив уже первой джобе.
        // WO-C8-41 HOLD r1 (F-A): ПЕРЕЗАПИСЬ, а не absent-only — одноимённая
        // переменная в containing scope переписывается fresh [null×N] (Zeebe
        // всегда создаёт коллекцию заново при входе в MI-тело). N=0-путь
        // (writeEmptyOutputCollection) остаётся absent-only — не в скопе F-A.
        precreateOutputCollection(processInstanceId, token, mi, count);
        String batchUuid = UUID.randomUUID().toString();
        ProcessVariable batchVar = new ProcessVariable();
        batchVar.setName("_mi_batch_" + miId);
        batchVar.setType(ProcessVariableType.STRING);
        batchVar.setValue(batchUuid);
        UUID batchScope = innermostScopeOfToken(token);
        if (batchScope == null) {
            dbService.setVariables(processInstanceId, List.of(batchVar));
        } else {
            dbService.setVariables(processInstanceId, batchScope, List.of(batchVar));
        }
        dbService.recordInclusiveExpected(processInstanceId, miId + "::" + batchUuid, count);
        int spawn = mi.isSequential() ? 1 : count;
        for (int i = 0; i < spawn; i++) {
            spawnMiInstance(processInstanceId, token, bpmnElement, mi, collection, i, executor);
        }
        log.info("{}/{}: Entering multi-instance {}: {} {} instance(s) batch={}", processInstanceId, token, miId, count, mi.isSequential() ? "sequential" : "parallel", batchUuid);
    }

    /**
     * Records a multi-instance instance's completion and reports whether the whole multi-instance is done.
     * For sequential MI not yet done, the next instance is started here.
     */
    public boolean multiInstanceContinue(UUID processInstanceId, UUID token, BpmnElementModel element, UUID completedActivityId) {
        return multiInstanceContinue(processInstanceId, token, element, completedActivityId, null);
    }

    /**
     * WO-C8-40: executor-параметр нужен sequential MI-подпроцессу (спaун следующего
     * инстанса стартует nested flow через {@code executor.execute}); task-хвосты
     * передают свой executor, остальные вызывающие — null (parallel-путь executor
     * не использует). Null допустим: parallel-ветка в него не заходит.
     */
    public boolean multiInstanceContinue(UUID processInstanceId, UUID token, BpmnElementModel element,
            UUID completedActivityId, TokenExecutor executor) {
        String miId = element.getId();
        // WO-ENG-7: resolve the per-entry batch UUID so bookkeeping is isolated between loop iterations
        String batchUuid = resolveBatchUuid(processInstanceId, miId, token);
        String gatewayKey = miId + "::" + batchUuid;
        MultiInstanceExtensionModel mi = element.getExtensions().getMultiInstanceExtension();
        dbService.recordParallelGatewayArrival(processInstanceId, gatewayKey, completedActivityId.toString());
        Integer expected = dbService.getInclusiveExpected(processInstanceId, gatewayKey);
        int arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, gatewayKey).size();

        boolean done = (expected != null && arrived >= expected) || completionConditionMet(processInstanceId, token, arrived, expected, mi);
        if (done) {
            // WO-C8-41 (F4, oracle S9) + WO-C8-57 (F-15, oracle S10/S10m/S10l/S11):
            // done РАНЬШЕ всех приходов (arrived < expected) — сработало
            // completionCondition: оставшиеся живые сиблинги MI ТЕРМИНИРУЮТСЯ.
            // Zeebe 8.5 (живая запись, docs 8.9 «Any instances that are still active
            // are terminated»): 0 живых после 1 завершения из 3 — и для MI-подпроцесса
            // (S9), и для MI-ЗАДАЧИ (S10: job'ов сиблингов больше не активировать).
            // ЕДИНЫЙ механизм для всех типов MI-элемента. При полном done
            // (arrived >= expected) живых сиблингов нет по определению — no-op.
            if (expected != null && arrived < expected) {
                cancelRemainingMiInstances(processInstanceId, element, completedActivityId);
            }
            dbService.clearParallelGatewayArrivals(processInstanceId, gatewayKey);
            return true;
        }
        if (mi.isSequential()) {
            // WO-C8-40: sequential-continue — та же chain-семантика, что enter
            // (вложенный sequential MI перечитывает коллекцию из внешних скоупов).
            spawnMiInstance(processInstanceId, token, element, mi,
                miInputCollection(processInstanceId, token, mi), arrived, executor);
            log.info("{}/{}: Multi-instance {} starting next sequential instance ({} of {} done)", processInstanceId, token, miId, arrived, expected);
        } else {
            log.info("{}: Multi-instance {} not ready: {} of {} instances done batch={}", processInstanceId, miId, arrived, expected, batchUuid);
        }
        return false;
    }

    /**
     * WO-C8-41 (F4) + WO-C8-57 (F-15): гашение живых сиблингов MI после раннего
     * срабатывания completionCondition. ЕДИНЫЙ механизм для всех типов MI-элемента:
     * <ul>
     *   <li>{@code SUB_PROCESS} (WO-C8-41, oracle S9): гасится строка инстанса и
     *       ВСЯ его scope-цепочка (внутренности — зеркало
     *       {@code EventTrigger.cancelMiSubProcessInScope});</li>
     *   <li>{@code SERVICE_TASK}/{@code USER_TASK} (WO-C8-57, oracle S10/S10m/S10l):
     *       гасится строка инстанса и его JOB (см. {@link #terminateSiblingJob}) —
     *       иначе у задачи остаётся живая работа (job в брокере / открытая задача
     *       в инбоксе), а поздний результат воркера приходит на «мёртвый» инстанс.</li>
     * </ul>
     * Только строки того же элемента (вложенные контейнеры — другой id, не трогаем).
     * Частичная агрегация уже записана позиционно (слоты терминированных — null)
     * и НЕ удаляется (S9-2/S10-2).
     */
    private void cancelRemainingMiInstances(UUID processInstanceId, BpmnElementModel miElement,
            UUID completedInstanceId) {
        List<com.zorrodev.bpm.engine.dto.Activity> live = dbService.getActiveActivities(processInstanceId);
        List<com.zorrodev.bpm.engine.dto.Activity> siblings = live.stream()
            .filter(a -> a != null && a.getId() != null
                && !a.getId().equals(completedInstanceId)
                && miElement.getId().equals(a.getBpmnElementId())
                && (a.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED
                    || a.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.IN_PROGRESS))
            .toList();
        if (siblings.isEmpty()) {
            return;
        }
        for (com.zorrodev.bpm.engine.dto.Activity sibling : siblings) {
            for (com.zorrodev.bpm.engine.dto.Activity inner : elementSupport.filterActivitiesInScope(
                processInstanceId, live, sibling.getId())) {
                dbService.cancelActivity(inner.getId());
            }
            dbService.cancelActivity(sibling.getId());
            terminateSiblingJob(sibling);
        }
        log.info("{}/{}: completionCondition terminated {} remaining MI instance(s)",
            processInstanceId, miElement.getId(), siblings.size());
    }

    /**
     * WO-C8-57 (F-15): гашение JOB'а терминированного сиблинга MI-задачи.
     *
     * <p>Zeebe 8.5: у терминированного инстанса job УДАЛЯЕТСЯ — его нельзя ни
     * активировать, ни завершить (живая запись oracle S10: late completion →
     * {@code NOT_FOUND}). У нас job MI-задачи — это строка {@code service_tasks}
     * (service task) либо {@code user_tasks} (user task): «больше не outstanding»
     * = проставленный {@code completed_at}. Без этого счётчик «активных заданий»
     * по определению (WO-C8-57) висел бы на терминированных сиблингах вечно.
     *
     * <p>Поздний результат воркера по такой задаче отвергается существующим
     * идемпотентным guard'ом {@code CompletionService.isCompletionAllowed}
     * (activity в статусе CANCELLED → completion поглощается как replay,
     * {@code alreadyCompleted=true}, состояние не меняется).
     */
    private void terminateSiblingJob(com.zorrodev.bpm.engine.dto.Activity sibling) {
        if (sibling.getType() == BpmnElementType.SERVICE_TASK) {
            dbService.completeServiceTask(sibling.getId());
        } else if (sibling.getType() == BpmnElementType.USER_TASK) {
            dbService.completeUserTask(sibling.getId());
        }
    }

    /**
     * WO-DIFF-3 (#4): writes a multi-instance instance's outputElement into
     * the outputCollection POSITIONALLY — at the instance's
     * {@code loopCounter - 1} slot — not in completion order. Zeebe collects
     * by loopCounter index regardless of which iteration finishes first;
     * the old completion-order append is kept only for the ad-hoc path
     * ({@link ElementSupport#appendToJsonList}), which has no index.
     * No-op unless both outputCollection and outputElement are configured.
     */
    public void aggregateMultiInstanceOutput(UUID processInstanceId, UUID scopeId, BpmnElementModel element) {
        MultiInstanceExtensionModel mi = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .orElse(null);
        if (mi == null || mi.getOutputCollection() == null || mi.getOutputCollection().isBlank()
            || mi.getOutputElement() == null || mi.getOutputElement().isBlank()) {
            return;
        }
        // WO-C8-41 (F1-уточнение): outputElement резолвится в chain-контексте
        // (root + внешние скоупы + own, pinpoint по именам — PERF-9 инвариант
        // сохранён): completion-переменная после F1 живёт в скоупе КОНТЕЙНЕРА
        // MI-подпроцесса, root+own merge её не видит (регресс S8b-класса).
        // WO-PERF-9 (B-8, full-scan): only the referenced names, not the merge
        // over the whole scope.
        Object value = scriptService.evaluateExpression(mi.getOutputElement(),
            elementSupport.chainVariablesForNames(processInstanceId, scopeId,
                FeelVariableNames.extract(mi.getOutputElement())));
        // WO-C8-41 (F3, oracle S8a/S8b): агрегация — в CONTAINING scope тела
        // (parent scope инстанса: root сверху, outer-instance scope у вложенного),
        // а не всегда в root. Иначе внутренняя агрегация утекает в корень
        // (S8a: innerResults в dump; S8b: tresults в dump), а Zeebe её дропает
        // со скоупом тела.
        UUID containingScope = containingScopeOfInstance(processInstanceId, scopeId);
        dbService.setJsonElementAt(processInstanceId, containingScope, mi.getOutputCollection(),
            miSlot(processInstanceId, scopeId) - 1,
            objectMapper.writeValueAsString(elementSupport.toJavaStructure(value)));
    }

    /**
     * WO-C8-41 (F3): containing scope MI-тела — parent scope его инстанса
     * (scopeActivityId токена строки инстанса); null = корень (верхний уровень).
     * Null-safe к мокам (нет activity/токена → корень, как раньше).
     */
    UUID containingScopeOfInstance(UUID processInstanceId, UUID instanceActivityId) {
        try {
            com.zorrodev.bpm.engine.dto.Activity activity = dbService.getActivity(instanceActivityId);
            if (activity == null || activity.getToken() == null) {
                return null;
            }
            return dbService.findToken(activity.getToken()).map(t -> t.getScopeActivityId()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * WO-C8-41 (F2, oracle S1-1/S4): предсоздание outputCollection
     * {@code [null x count]} в containing scope входа. Без
     * outputCollection — no-op.
     * <p>
     * WO-C8-41 HOLD r1 (F-A, Zeebe-parity): ВСЕГДА перезаписывать, а не
     * absent-only. Zeebe при входе в MI-тело всегда создаёт outputCollection
     * заново (repro 8.5.0: preseeded results=["stale"×3], MI по 2 элементам →
     * ["a","b"], хвост не протекает). Ранний return по «существующей
     * переменной» оставлял чужие/старые элементы в агрегате (у нас было
     * [null,null,"stale"]). setVariables — upsert по (pi, name, scope):
     * перезапись существующей строки, не вставка дубля.
     */
    private void precreateOutputCollection(UUID processInstanceId, UUID token,
            MultiInstanceExtensionModel mi, int count) {
        if (mi.getOutputCollection() == null || mi.getOutputCollection().isBlank()) {
            return;
        }
        UUID containingScope = innermostScopeOfToken(token);
        ProcessVariable pre = new ProcessVariable();
        pre.setName(mi.getOutputCollection());
        pre.setType(ProcessVariableType.JSON);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("null");
        }
        sb.append("]");
        pre.setValue(sb.toString());
        if (containingScope == null) {
            dbService.setVariables(processInstanceId, List.of(pre));
        } else {
            dbService.setVariables(processInstanceId, containingScope, List.of(pre));
        }
        log.info("{}/{}: Multi-instance pre-created {} as {} nulls in scope {}",
            processInstanceId, token, mi.getOutputCollection(), count,
            containingScope == null ? "root" : containingScope);
    }

    // ─── Private MI-only helpers ───────────────────────────────────────────

    private void spawnMiInstance(UUID processInstanceId, UUID token, BpmnElementModel element, MultiInstanceExtensionModel mi, Object collection, int index, TokenExecutor executor) {
        // WO-C8-40: вход MI-подпроцесса делегируется spawnMiSubProcessInstance —
        // там свой порядок (контейнерная строка + привязка + chain + токен + старт),
        // общий хвост ниже (boundary-расписание) един для всех видов.
        if (element.getType() == BpmnElementType.SUB_PROCESS) {
            spawnMiSubProcessInstance(processInstanceId, token, element, mi, collection, index, executor);
            return;
        }
        UUID activityId = dbService.createActivity(processInstanceId, token, element);
        // Job-based (service/user/call-activity): outputElement предобъявляется
        // в payload каждого инстанса (F1 корень 6).
        bindMiInstanceVariables(processInstanceId, activityId, mi, collection, index, true);
        if (element.getType() == BpmnElementType.USER_TASK) {
            // WO-IN-4: assignee/кандидатов резолвим в scope инстанса, а не в root:
            // inputElement живёт scope-локально, root-контекст его не видит —
            // колонка assignee оставалась NULL и фильтр ?assignee=X давал total 0.
            String resolvedAssignee = elementSupport.resolveAssigneeInScope(processInstanceId, activityId, element);
            String resolvedGroups = elementSupport.resolveCandidateGroupsInScope(processInstanceId, activityId, element);
            // WO-IN-3: зеркало прямого пути — MI-инстанс тоже обязан писать кандидатов,
            // иначе задача внутри MI осталась бы невидимой для фильтра по кандидату.
            String resolvedUsers = elementSupport.resolveCandidateUsersInScope(processInstanceId, activityId, element);
            String formKey = element.getExtensions() != null && element.getExtensions().getUserTaskExtension() != null
                ? element.getExtensions().getUserTaskExtension().getFormKey() : null;
            // WO-C8-22: linked-form id rides its own field into the row (never into formKey).
            String formId = element.getExtensions() != null && element.getExtensions().getUserTaskExtension() != null
                ? element.getExtensions().getUserTaskExtension().getFormId() : null;
            // WO-C8-23: binding rides alongside (mirror of the UserTaskHandler path).
            String bindingType = element.getExtensions() != null && element.getExtensions().getUserTaskExtension() != null
                ? element.getExtensions().getUserTaskExtension().getBindingType() : null;
            String resolvedDueDate = elementSupport.resolveDueDateInScope(processInstanceId, activityId, element);
            String resolvedFollowUpDate = elementSupport.resolveFollowUpDateInScope(processInstanceId, activityId, element);
            // WO-C8-30: same resolve-or-incident as the UserTaskHandler path (mirror it —
            // MI instances resolve independently; a broken expression halts this
            // instance with an incident instead of a silent default).
            // WO-IN-4 раунд 2, О-1: priority — в scope инстанса, как assignee
            // (root не видел =inputElement).
            final int resolvedPriority;
            try {
                resolvedPriority = elementSupport.resolveUserTaskPriorityInScopeOrThrow(processInstanceId, activityId, element);
            } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException e) {
                // WO-ENG-24: см. cardinality-catch выше — перегрузка идёт до
                // 503-хендлера, битый priorityDefinition — в инцидент ниже.
                throw e;
            } catch (EngineException e) {
                log.warn("{}/{}: {}", processInstanceId, activityId, e.getMessage());
                dbService.errorActivity(activityId);
                dbService.createIncident(activityId, e.getMessage());
                return;
            }
            dbService.createUserTask(activityId, resolvedAssignee, resolvedGroups, resolvedUsers, formKey, formId, bindingType, resolvedDueDate, resolvedFollowUpDate, resolvedPriority);
        } else {
            dbService.createServiceTask(activityId, elementSupport.serviceTaskRetries(element), elementSupport.serviceTaskJob(element));
            elementSupport.applyIoMappings(processInstanceId, activityId, element, true);
            serviceTaskEnqueueService.enqueueAfterCommit(activityId);
        }
        // WO-ENG-3: schedule boundary timers/messages/signals on each MI instance's activity
        boundaryScheduler.scheduleBoundaryTimers(processInstanceId, activityId, element);
        boundaryScheduler.scheduleMessageBoundaries(processInstanceId, activityId, element);
        boundaryScheduler.scheduleSignalBoundaries(processInstanceId, activityId, element);
    }

    /**
     * WO-C8-40: спаун одного инстанса MI-подпроцесса. Порядок — зеркало одиночного
     * входа {@code SubProcessHandler} (контейнерная строка → chain-bound seed →
     * привязка цикла → дочерний токен → старт внутреннего start event), ПЛЮС:
     * {@code bindMiInstanceVariables} ДО chain (seed видит inputElement, WO-DIFF-1),
     * boundary-расписание на инстанс (WO-ENG-3). Контейнерная ioMapping-output
     * здесь НЕ применяется (выходы контейнера — только на MI-выходе после join).
     */
    private void spawnMiSubProcessInstance(UUID processInstanceId, UUID token,
            BpmnElementModel element, MultiInstanceExtensionModel mi, Object collection, int index,
            TokenExecutor executor) {
        UUID activityId = dbService.createActivity(processInstanceId, token, element);
        // Привязка цикла ПЕРВОЙ — chain-bound seed и внутренние элементы видят
        // inputElement/loopCounter с первой строки (порядок WO-DIFF-1 + MI).
        // jobBased=false: у MI-подпроцесса job'а нет — outputElement НЕ
        // предобъявляется (C8-41 F2-бис, oracle S1-1: tu отсутствует при входе;
        // появляется только на выходе инстанса, при оценке output mapping).
        bindMiInstanceVariables(processInstanceId, activityId, mi, collection, index, false);
        elementSupport.applyChainedIoInputs(processInstanceId, activityId, element);
        SubProcessHandler sub = subProcessHandlerProvider.getIfAvailable();
        if (sub == null) {
            throw new EngineException("Multi-instance " + element.getId()
                + ": SubProcessHandler bean unavailable for instance spawn");
        }
        sub.startNestedFlow(processInstanceId, token, activityId, element.getProcessDefinition(),
            element, executor, true);
        // WO-ENG-3: границы контейнера взводятся на каждый инстанс — зеркало
        // одиночного входа (тот же trio, тот же относительный порядок).
        boundaryScheduler.scheduleBoundaryTimers(processInstanceId, activityId, element);
        boundaryScheduler.scheduleMessageBoundaries(processInstanceId, activityId, element);
        boundaryScheduler.scheduleSignalBoundaries(processInstanceId, activityId, element);
    }

    /**
     * Resolves the instance count from the already-fetched root variables and
     * the already-evaluated input collection (WO-REL-41, B-8 п.3 — the caller
     * shares both instead of re-fetching/re-evaluating on the same transition).
     * The sequential-continue path ({@link #multiInstanceContinue}) keeps its
     * own fetch via {@link #miInputCollection(UUID, MultiInstanceExtensionModel)} —
     * a different transition, not this one.
     */
    private int resolveCardinality(BpmnElementModel element, List<ProcessVariable> variables, Object collection) {
        MultiInstanceExtensionModel mi = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .orElseThrow(() -> new EngineException("Multi-instance " + element.getId() + " has no loop characteristics"));

        if (mi.getInputCollection() != null && !mi.getInputCollection().isBlank()) {
            return collectionSize(element, mi, variables, collection);
        }

        String expression = mi.getCardinality();
        if (expression == null || expression.isBlank()) {
            throw new EngineException("Multi-instance " + element.getId() + " has neither inputCollection nor loopCardinality");
        }
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        Object value = scriptService.evaluateExpression(expression, variables);
        if (!(value instanceof Number number)) {
            throw new EngineException("Multi-instance " + element.getId() + " cardinality did not evaluate to a number: " + value);
        }
        // WO-REL-31 F23: Number.intValue() silently TRUNCATES fractions (2.5 → 2) and WRAPS
        // out-of-int-range values (3_000_000_000 → −1_294_967_296) — neither is a valid instance
        // count. intValueExact() on the exact decimal representation raises ArithmeticException
        // for both, which we turn into an EngineException (→ incident in enter()).
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException e) {
            throw new EngineException("Multi-instance " + element.getId() + " cardinality is not a valid whole number in int range: " + number);
        }
    }

    /**
     * F23: parks the token at the multi-instance element with a visible incident. Creates the
     * element's activity row first (precedent: spawnMiInstance's resolveUserTaskPriorityOrThrow
     * path) — IncidentService.raiseIncident would silently skip an element without an activity.
     */
    private void raiseCardinalityIncident(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement, String message) {
        log.warn("{}/{}: {}", processInstanceId, token, message);
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.errorActivity(activityId);
        dbService.createIncident(activityId, message);
    }

    /**
     * WO-C8-51 (B): принимается только настоящая последовательность. Прежняя
     * рефлексия по {@code size()} принимала и scala-Map (FEEL-объект): объект
     * молча порождал N инстансов по числу полей с {@code item=null} в каждом,
     * без инцидента. Zeebe на контекст вместо списка поднимает инцидент — теперь
     * и мы (проверка — тем же {@code toJavaStructure}, что у ad-hoc).
     */
    private int collectionSize(BpmnElementModel element, MultiInstanceExtensionModel mi,
            List<ProcessVariable> variables, Object collection) {
        // Обычный случай (java-список после evaluateExpression) не требует
        // конверсии; к toJavaStructure идём только для scala/Jackson-типов.
        if (collection instanceof List<?> list) {
            return list.size();
        }
        Object javaValue = elementSupport.toJavaStructure(collection);
        if (javaValue instanceof List<?> list) {
            return list.size();
        }
        throw new EngineException("Multi-instance " + element.getId() + " inputCollection did not evaluate to a list: "
            + CollectionNotAListDiagnosis.describe(mi.getInputCollection(), collection, variables));
    }

    private Object miInputCollection(UUID processInstanceId, UUID token, MultiInstanceExtensionModel mi) {
        if (mi.getInputElement() == null || mi.getInputElement().isBlank()
            || mi.getInputCollection() == null || mi.getInputCollection().isBlank()) {
            return null;
        }
        // WO-PERF-9 (B-8, full-scan): sequential-continue re-evaluates the
        // inputCollection on its own transition — pinpoint, same as enter().
        // WO-C8-40: контекст — chain токена, как в enter (вложенный MI).
        return scriptService.evaluateExpression(mi.getInputCollection(),
            chainVariablesFor(processInstanceId, token, mi.getInputCollection()));
    }

    /**
     * @param jobBased true для MI-элементов с job'ом (service/user/call-activity)
     *                 — outputElement предобъявляется (F1 корень 6, live 8.5:
     *                 {@code r:null} в payload каждого инстанса); false для
     *                 MI-подпроцесса — предобъявления нет (C8-41 F2-бис, oracle
     *                 S1-1: переменная отсутствует при входе, создаётся на
     *                 выходе инстанса при оценке output mapping).
     */
    private void bindMiInstanceVariables(UUID processInstanceId, UUID scopeId, MultiInstanceExtensionModel mi, Object collection, int index, boolean jobBased) {
        List<ProcessVariable> locals = new ArrayList<>();
        if (collection != null && mi.getInputElement() != null && !mi.getInputElement().isBlank()) {
            locals.add(elementSupport.toProcessVariable(mi.getInputElement(), collectionElement(collection, index)));
        }
        ProcessVariable loopCounter = new ProcessVariable();
        loopCounter.setName("loopCounter");
        loopCounter.setType(ProcessVariableType.LONG);
        loopCounter.setValue(Long.toString(index + 1L));
        locals.add(loopCounter);
        // WO-C8-53 (F1 корень 6, live Zeebe 8.5.0): outputElement — ЛОКАЛЬНАЯ
        // переменная инстанса: её несёт job payload КАЖДОГО инстанса (typed null
        // до завершения воркером со значением), а протечки в следующий инстанс
        // и в root нет (r из инстанса a не виден инстансу b и не виден dump).
        // Значение — JSON-тип со строкой "null": скалярный null строкой не
        // персистится (оба upsert-пути коэрсят null→"", WO-C8-41 F2-бис), а
        // JSON "null" читается как честная null и в FEEL-контексте, и в
        // job payload. Предобъявляется только простой FEEL-идентификатор
        // (парсер хранит выражение без ведущего "=", BpmnParseServiceImpl):
        // сложные выражения ({"decision": decision, ...}) переменную не
        // создают. Имя, занятое inputElement/loopCounter, не перезаписываем
        // (upsert в locals — последнее wins, входной байнд ушёл бы в null).
        // Только job-based элементы: у MI-подпроцесса job'а нет и переменная
        // при входе отсутствует (C8-41 F2-бис, oracle S1-1/S5-1 — живые
        // прогоны на 8.5.0).
        String outputElement = mi.getOutputElement();
        if (jobBased && outputElement != null && !outputElement.isBlank()
            && outputElement.matches("^[A-Za-z_][A-Za-z0-9_]*$")
            && !outputElement.equals("loopCounter")
            && (mi.getInputElement() == null || !outputElement.equals(mi.getInputElement()))) {
            ProcessVariable outputElementVar = new ProcessVariable();
            outputElementVar.setName(outputElement);
            outputElementVar.setType(ProcessVariableType.JSON);
            outputElementVar.setValue("null");
            locals.add(outputElementVar);
        }
        // WO-C8-41 (F2-бис): скалярные null-предобъявления (tu:null в oracle
        // S1-1/S5-1) строкой НЕ persist'ятся: оба upsert-пути (PG+H2) коэрсят
        // null→"" и =neverSet давал бы "" вместо null (S5 ["",""] вместо
        // [null,null]). «Не задано» = отсутствие строки; lenient-missing
        // оценка (ENG-30 дефолт) даёт null идентично. Предсоздаётся только
        // outputCollection (JSON "[null×N]" — настоящие null внутри текста).
        dbService.setVariables(processInstanceId, scopeId, locals);
    }

    private void appendToJsonList(UUID processInstanceId, String name, Object value) {
        // WO-C8-32: single shared mechanism in ElementSupport (ad-hoc output aggregation
        // reuses it); this delegate keeps MI behaviour byte-identical.
        elementSupport.appendToJsonList(processInstanceId, name, value);
    }

    /**
     * WO-DIFF-3 (#4): the completing instance's 1-based {@code loopCounter}
     * (written by {@link #bindMiInstanceVariables} at spawn). Sourced from
     * the instance's own scope — never from a stale root copy — so a
     * concurrent completion of a sibling cannot shift this write's slot.
     */
    private int miSlot(UUID processInstanceId, UUID scopeId) {
        // WO-PERF-9 (B-8, full-scan): only the loopCounter row is needed —
        // one indexed read, never the scope merge.
        return dbService.getScopedVariablesByNames(processInstanceId, scopeId, Set.of("loopCounter")).stream()
            .filter(v -> "loopCounter".equals(v.getName()))
            .findFirst()
            .map(v -> {
                try {
                    return Integer.parseInt(v.getValue().trim());
                } catch (NumberFormatException e) {
                    throw new EngineException("Multi-instance instance has a non-numeric loopCounter: " + v.getValue());
                }
            })
            .orElseThrow(() -> new EngineException("Multi-instance instance has no loopCounter in its scope"));
    }

    /**
     * WO-DIFF-3 (#6): creates the output variable as an empty JSON array when
     * the MI entry spawns zero instances. Only when outputCollection is
     * configured (no output name → nothing to create); never overwrites an
     * existing value (re-entry over a completed MI keeps prior results).
     */
    private void writeEmptyOutputCollection(UUID processInstanceId, MultiInstanceExtensionModel mi) {
        if (mi.getOutputCollection() == null || mi.getOutputCollection().isBlank()) {
            return;
        }
        if (dbService.getVariableTextValue(processInstanceId, mi.getOutputCollection()).isPresent()) {
            return;
        }
        ProcessVariable empty = new ProcessVariable();
        empty.setName(mi.getOutputCollection());
        empty.setType(ProcessVariableType.JSON);
        empty.setValue("[]");
        dbService.setVariables(processInstanceId, List.of(empty));
    }

    private Object collectionElement(Object collection, int index) {
        if (collection instanceof java.util.List<?> list) {
            return index < list.size() ? list.get(index) : null;
        }
        if (collection != null) {
            try {
                return collection.getClass().getMethod("apply", int.class).invoke(collection, index);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    /**
     * Reads the per-entry batch UUID written by {@link #enter}. Falls back to {@code miId} for
     * legacy instances that were started before this fix (WO-ENG-7). The fallback is safe — it
     * merely opts out of iteration isolation for those instances (which may still suffer the old
     * bookkeeping collision, but that is pre-existing and will be resolved as those instances
     * complete their first MI entry).
     * <p>
     * WO-REL-41 (B-8, п.2): ONE pinpoint row read, not the full variable list.
     * <p>
     * WO-C8-40: batch ищется сначала в innermost scope токена завершающейся
     * задачи (вложенный MI пишет его в scope внешнего инстанса), потом в root.
     * Токен continue совпадает с токеном enter (spawn идёт на нём же), поэтому
     * scope тот же, что при записи. Null-safe к мокам (findToken → null).
     */
    private String resolveBatchUuid(UUID processInstanceId, String miId, UUID token) {
        String name = "_mi_batch_" + miId;
        UUID innerScope = innermostScopeOfToken(token);
        if (innerScope != null) {
            Optional<String> scoped = dbService
                .getScopedVariablesByNames(processInstanceId, innerScope, Set.of(name)).stream()
                .filter(v -> name.equals(v.getName()))
                .map(ProcessVariable::getValue)
                .findFirst();
            if (scoped.isPresent()) {
                return scoped.get();
            }
        }
        return dbService.getVariableTextValue(processInstanceId, name)
            .orElse(miId);
    }

    /**
     * WO-C8-40: innermost scope токена (scopeActivityId самого токена) —
     * null для корневых токенов и для моков (findToken → null). Не путать с
     * {@link #scopeChainOfToken} (вся цепочка): batch пишется/читается только
     * в непосредственном scope входа.
     */
    private UUID innermostScopeOfToken(UUID tokenId) {
        if (tokenId == null) {
            return null;
        }
        try {
            Optional<com.zorrodev.bpm.engine.dto.Token> token = dbService.findToken(tokenId);
            if (token == null || token.isEmpty()) {
                return null;
            }
            return token.get().getScopeActivityId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean completionConditionMet(UUID processInstanceId, UUID token, int arrived, Integer expected, MultiInstanceExtensionModel mi) {
        String expression = mi.getCompletionCondition();
        if (expression == null || expression.isBlank()) {
            return false;
        }
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        // Build evaluation variables: process variables + MI-scope locals.
        // WO-ENG-8: inject completedInstances/totalInstances that the completionCondition may reference.
        // These are NOT persisted — they exist only for the duration of this FEEL evaluation.
        // NOTE: use evaluateExpression (not evaluateScript) because completionCondition is a FEEL expression,
        // not a unary test. evaluateScript would misinterpret the expression.
        // WO-PERF-9 (B-8, full-scan): the condition only needs its own
        // referenced names — one indexed SELECT, not the whole scope.
        // WO-C8-45 (г): контекст — chain токена продолжающегося инстанса
        // (root + внешние скоупы, nearest-wins): outputCollection вложенного MI
        // лежит в containing scope тела (C8-41 F3), а не в root — root-чтение
        // условие никогда не срабатывало. Сверху цепочка пуста: ровно
        // rootVariablesFor, поведение прежнее.
        List<ProcessVariable> variables = new ArrayList<>(elementSupport.chainVariablesForTokenNames(
            processInstanceId, token, extractFeelVariableNames(expression)));
        long total = expected != null ? expected : 0;
        long active = expected != null ? Math.max(0, expected - arrived) : 0;
        addLocalLong(variables, "completedInstances", arrived);
        // WO-C8-41 (F4, oracle S9): документированное Camunda-8 имя счётчика —
        // numberOfCompletedInstances (его не было, условие с ним никогда не
        // срабатывало). Алиасы ZBPM ниже — для совместимости.
        addLocalLong(variables, "numberOfCompletedInstances", arrived);
        addLocalLong(variables, "totalInstances", total);
        addLocalLong(variables, "numberOfInstances", total);
        addLocalLong(variables, "numberOfCompleteInstances", arrived);
        addLocalLong(variables, "numberOfActiveInstances", active);
        addLocalLong(variables, "numberOfTerminatedInstances", 0);
        Object result = scriptService.evaluateExpression(expression, variables);
        return Boolean.TRUE.equals(result);
    }

    /** Adds a long-valued ProcessVariable to the list for FEEL evaluation scope (not persisted). */
    private void addLocalLong(List<ProcessVariable> variables, String name, long value) {
        ProcessVariable var = new ProcessVariable();
        var.setName(name);
        var.setType(ProcessVariableType.LONG);
        var.setValue(Long.toString(value));
        variables.add(var);
    }
}
