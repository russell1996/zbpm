package com.zorrodev.bpm.engine.handler;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-35 раунд 5, ШАГ 2 (BLOCKER-4, требование CTO): охранный тест на ВСЕ места деактивации.
 *
 * <p>Дефект нашла независимая рецензия: перепроверка припаркованных inclusive-join'ов стояла
 * вручную на шести точках деактивации, и {@code ErrorEscalationThrower} — единственное место,
 * где умирает хост со своей error/escalation-границей, — в списке не было. Один пропущенный
 * класс даёт ровно то, что описано в BLOCKER-4: инстанс RUNNING вечно, {@code taskAfter} не
 * создан, инцидента нет — «всё выглядит работающим».
 *
 * <p>Тест находит ВСЕ места, переводящие activity в неактивное состояние
 * ({@code dbService.cancelActivity / completeActivity / errorActivity / cancelActiveActivities /
 * cancelActiveActivitiesForToken}), и требует, чтобы такой путь ЛИБО вызывал перепроверку
 * припаркованных join'ов <b>в своей же ветке</b>, ЛИБО был внесён в явный {@link #WHITELIST}
 * с причиной. Гранулярность — по ветке, а не по методу: иначе снятие перепроверки в ОДНОЙ
 * ветке метода проходило бы незаметно (метод всё ещё содержит вызов в соседней ветке).
 *
 * <p>Три ловушки против «тест, который не может краснеть»:
 * <ul>
 *   <li>{@link #EXPECTED_SITE_COUNT} — добавление или удаление любого места деактивации валит тест,
 *       пока список не пересмотрен;</li>
 *   <li>запись белого списка, которой на диске больше нет, валит тест — список нельзя раздуть
 *       заранее и нельзя оставить устаревшей;</li>
 *   <li>причина обязана быть непустой у каждой записи.</li>
 * </ul>
 *
 * <p>Разбор источника свой, без внешних библиотек: строковые литералы и комментарии затираются
 * (с сохранением переводов строк и длины), дальше — подсчёт скобок и стек блоков. Метод
 * определяется по строке с открывающей скобкой: имя — последний токен перед первой {@code (} в
 * склеенной сигнатуре; блоки {@code if/for/try/lambda} наследуют имя внешнего метода, но помнят
 * свою строку — она идёт в ключ белого списка.
 *
 * <p>Известное упрощение (честно): два разных блока одного метода с ОДИНАКОВОЙ строкой
 * открытия и одинаковым выражением деактивации склеились бы в один ключ. На этом дереве таких
 * пар нет (проверено перечислением), и столкновение лишь ослабит проверку, а не сделать её
 * зелёной на пустом месте.
 */
class DeactivationWakeupCoverageTest {

    private static final Path MAIN = Path.of("src/main/java");

    /**
     * Число мест деактивации на этом дереве. ЛЮБОЕ его изменение валит тест: новое место
     * обязано быть либо разобрано (перепроверка в его ветке), либо внесено в белый список.
     */
    private static final int EXPECTED_SITE_COUNT = 80;

    /** site key -> причина, почему перепроверка здесь не нужна. Непустая — проверяется тестом. */
    private static final Map<String, String> WHITELIST = new LinkedHashMap<>();
    private static final String R01 =
        "собственное продолжение элемента: activity этого элемента закрывается, и ветвь идёт "
        + "дальше в том же хвосте (proceedToOutgoing/processFlow/executor.execute). Пока "
        + "элемент жив, он сам — возможный доставщик в припаркованный join, поэтому "
        + "перепроверка здесь была бы вредной; её делает вызывающий хвост "
        + "(CompletionService.finishUserTaskCompletion / finishServiceTaskCompletion / signal, "
        + "EventTrigger.fireBoundary, InclusiveGatewayHandler.handle) ";
    private static final String R02 =
        "элемент закрывается и НИЧЕГО не продолжает (нет исходящих потоков / ветвь уходит в "
        + "proceedToOutgoing вызывающего): доставлять тут нечему, перепроверку делает "
        + "вызывающий хвост ";
    private static final String R03 =
        "ad-hoc scope закрывается: гасятся его СОБСТВЕННЫЕ незавершённые activity на этом же "
        + "токене (cancelRemainingInstances), контейнер уже COMPLETED и в том же методе идёт "
        + "proceedToOutgoing. Это хвост одного scope, а не последний доставчик ЧУЖОГО join ";
    private static final String R04 =
        "неподдерживаемый тип элемента: токен НЕ паркуется, а уходит в ERROR + инцидент прямо "
        + "здесь (движок не может его выполнить) — перечитывать правило готовности некому ";
    private static final String R05 =
        "исход ВИДЕН: activity уходит в ERROR и поднимается инцидент — оператор резолвит его "
        + "через IncidentService.resolveIncident, который переисполняет элемент на том же "
        + "токене. Тихая поломка (инстанс RUNNING без инцидента) — не этот класс путей ";
    private static final String R06 =
        "отказ слушателя исчерпал бюджет: activity в ERROR + инцидент (исход виден оператору) ";
    private static final String R07 =
        "старая ERROR-строка отменяется, а элемент сразу переисполняется на том же токене "
        + "(executor.execute в этом же методе) — ветвь не умирает ";
    private static final String R08 =
        "погашен ВЕСЬ scope (scope сабпроцесса / scope-контейнер границы), все его внутренние "
        + "ветви умирают вместе с ним. Воскрешать припаркованный join этого scope значит "
        + "выполнить хвост ОТМЕНЁННОГО потока — контрпример @verifier раунда 4 (POF-2) ";
    private static final String R09 =
        "TERMINATE: гасится весь инстанс и доводится до конца в этом же хвосте. У "
        + "припаркованного join этого инстанса нет продолжения — поднимать его некому ";
    private static final String R10 =
        "прерывающий event-subprocess ЗАМЕЩАЕТ основной поток по BPMN: его запуск гасит "
        + "activity всего инстанса и доводит инстанс до конца. Просыпать тут join = пускать "
        + "хвост отменённого scope (контрпример раунда 4) ";
    private static final String R11 =
        "scope-контейнер границы: гасятся activity СВОЕГО scope и его ДОЧЕРНИЕ ИНСТАНСЫ (call "
        + "activity). Родительский инстанс этим не тронут — его припаркованные join-ы не теряют "
        + "доставщика ";
    private static final String R12 =
        "ветка ПРЕРЫВАЮЩЕЙ границы в EventTrigger.fireBoundary: хост (или его MI-копии) "
        + "гасятся, и перепроверка припаркованных join-ов для ВСЕХ вариантов этой ветки стоит "
        + "единственным вызовом в конце той же ветки interrupting — дублировать её в каждом "
        + "варианте нельзя ";
    private static final String R13 =
        "событийный шлюз: отменяются ПРОИГРАВШИЕ catch-братья, победитель идёт дальше в том "
        + "же signal(), где перепроверка припаркованных join-ов уже стоит "
        + "(CompletionService.signal, после proceedToOutgoing) ";
    private static final String R14 =
        "это САМА строка срабатывающего join-а: activity join-а закрывается, а продолжение "
        + "идёт вниз в том же методе (processFlow + executor.execute). Перепроверка здесь "
        + "будила бы саму себя — бесконечный шаг resume; её место после continue ";

    static {
        // ── собственное продолжение элемента: activity этого элемента закрывается, и…
        WHITELIST.put("CompensationThrowHandler.java#processCompensationThrow[if (!elementSupport.compensationThrowerHasPending(processInstanceId, activityId, bpmn, tar]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("CompletionService.java#resumeParkedCompensationThrowers[forceTerminalHandlerIds)) {]|dbService.completeActivity(thrower.getId())", R01);
        WHITELIST.put("ConditionalCatchHandler.java#handle[if (conditionHolds(bpmnElement, variables)) {]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("EndEventHandler.java#handle[]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("EventBasedGatewayHandler.java#handle[]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("ExclusiveGatewayHandler.java#handle[if (defaultFlowId == null) {]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("ExclusiveGatewayHandler.java#handle[} else if (outgoings.size() == 1 && incoming.size() == 1) {]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("ExclusiveGatewayHandler.java#handle[} else if (outgoings.size() == 1 && incoming.size() > 1) {]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("FlowNavigator.java#finishAdHocScope0[if (key != null) {]|dbService.completeActivity(scope.getId())", R01);
        WHITELIST.put("FlowNavigator.java#finishBranch[&& subProcessElement.getExtensions().getIoMappingExtension() != null) {]|dbService.completeActivity(subProcessActivityId)", R01);
        WHITELIST.put("FlowNavigator.java#finishBranch[if (parentActivityId != null) {]|dbService.completeActivity(parentActivityId)", R01);
        WHITELIST.put("IntermediateThrowEventHandler.java#handle[]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("LinkThrowHandler.java#handle[]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("MessageThrowHandler.java#handle[]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("ParallelGatewayHandler.java#handle[if (incomings.size() == 1 && outgoings.size() == 1 && !isSelfLoop(bpmn, bpmnElement, outgo]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("ParallelGatewayHandler.java#handle[if (incomings.size() == 1) {]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("ParallelGatewayHandler.java#handle[if (reached) {]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("SignalThrowHandler.java#handle[]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("StartThrowEventHandler.java#handle[]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("SyncTaskHandler.java#handle[]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("SyncTaskHandler.java#handle[if (ext.getResultVariable() != null && !ext.getResultVariable().isBlank()) {]|dbService.completeActivity(activityId)", R01);
        WHITELIST.put("SyncTaskHandler.java#handle[if (resultVariable != null && !resultVariable.isBlank()) {]|dbService.completeActivity(activityId)", R01);
        // ── элемент закрывается и НИЧЕГО не продолжает (нет исходящих потоков / ветв…
        WHITELIST.put("FlowNavigator.java#processFlow[if (flowActivityId != null) {]|dbService.completeActivity(flowActivityId)", R02);
        // ── ad-hoc scope закрывается: гасятся его СОБСТВЕННЫЕ незавершённые activity…
        WHITELIST.put("FlowNavigator.java#finishAdHocScope0[for (Activity mate : mates) {]|dbService.cancelActivity(mate.getId())", R03);
        // ── неподдерживаемый тип элемента: токен НЕ паркуется, а уходит в ERROR + ин…
        WHITELIST.put("ActivityServiceImpl.java#execute[if (handler == null) {]|dbService.errorActivity(activityId)", R04);
        // ── исход ВИДЕН: activity уходит в ERROR и поднимается инцидент — оператор р…
        WHITELIST.put("ActivityServiceImpl.java#throwServiceTaskError[if (handled) {]|dbService.errorActivity(serviceTaskId)", R05);
        WHITELIST.put("AdHocSubProcessHandler.java#raiseIncident[]|dbService.errorActivity(activityId)", R05);
        WHITELIST.put("CompensationThrowHandler.java#runCompensation[} catch (RuntimeException e) {]|dbService.errorActivity(activityId)", R05);
        WHITELIST.put("ElementListenerPhaseService.java#failPhaseListener[if (remaining > 0) {]|dbService.errorActivity(activityId)", R05);
        WHITELIST.put("EndEventHandler.java#handle[if (!handled) {]|dbService.errorActivity(activityId)", R05);
        WHITELIST.put("IncidentService.java#raiseIncident[if (activityId == null) {]|dbService.errorActivity(activityId)", R05);
        WHITELIST.put("MultiInstanceExecutor.java#raiseCardinalityIncident[]|dbService.errorActivity(activityId)", R05);
        WHITELIST.put("MultiInstanceExecutor.java#spawnMiInstance[} catch (EngineException e) {]|dbService.errorActivity(activityId)", R05);
        WHITELIST.put("UserTaskHandler.java#createTaskRow[} catch (com.zorrodev.bpm.contract.exception.EngineException e) {]|dbService.errorActivity(activityId)", R05);
        // ── отказ слушателя исчерпал бюджет: activity в ERROR + инцидент (исход виде…
        WHITELIST.put("CompletionService.java#failAssigningListener[if (remaining > 0) {]|dbService.errorActivity(serviceTaskId)", R06);
        WHITELIST.put("CompletionService.java#failCancelingListener[if (remaining > 0) {]|dbService.errorActivity(serviceTaskId)", R06);
        WHITELIST.put("CompletionService.java#failCompletingListener[if (remaining > 0) {]|dbService.errorActivity(serviceTaskId)", R06);
        WHITELIST.put("CompletionService.java#failCreatingListener[if (remaining > 0) {]|dbService.errorActivity(serviceTaskId)", R06);
        WHITELIST.put("CompletionService.java#failSharedBudget[if (remaining > 0) {]|dbService.errorActivity(serviceTaskId)", R06);
        WHITELIST.put("CompletionService.java#failUpdatingListener[if (remaining > 0) {]|dbService.errorActivity(serviceTaskId)", R06);
        WHITELIST.put("CompletionService.java#handleAssigningListeners[|| activity.getStatus() == ActivityStatus.IN_PROGRESS)) {]|dbService.errorActivity(serviceTaskId)", R06);
        WHITELIST.put("CompletionService.java#handleCancelingListeners[if (pendingCanceling >= 0 && pendingCanceling < cancelingListenersRt.size()) {]|dbService.errorActivity(serviceTaskId)", R06);
        WHITELIST.put("CompletionService.java#handleUpdatingListeners[if (pendingUpdating + 1 < updatingListenersRt.size()) {]|dbService.errorActivity(serviceTaskId)", R06);
        // ── старая ERROR-строка отменяется, а элемент сразу переисполняется на том ж…
        WHITELIST.put("IncidentService.java#resolveIncident[if (variables != null && !variables.isEmpty()) {]|dbService.cancelActivity(incident.getActivityId())", R07);
        // ── погашен ВЕСЬ scope (scope сабпроцесса / scope-контейнер границы), все ег…
        WHITELIST.put("CancelEndHandler.java#processCancelEnd[]|dbService.completeActivity(activityId)", R08);
        WHITELIST.put("CancelEndHandler.java#processCancelEnd[if (endToken == null || endToken.getScopeActivityId() == null) {]|dbService.cancelActiveActivitiesForToken(tokenId)", R08);
        WHITELIST.put("CancelEndHandler.java#processCancelEnd[if (endToken == null || endToken.getScopeActivityId() == null) {]|dbService.cancelActivity(scopeActivityId)", R08);
        WHITELIST.put("ErrorEscalationThrower.java#throwError[if (boundary != null) { #2]|dbService.cancelActiveActivitiesForToken(tok.getId())", R08);
        WHITELIST.put("ErrorEscalationThrower.java#throwError[if (boundary != null) { #2]|dbService.cancelActivity(scope.getId())", R08);
        WHITELIST.put("ErrorEscalationThrower.java#throwEscalation[if (isInterrupting(boundary)) {]|dbService.cancelActiveActivitiesForToken(tok.getId())", R08);
        WHITELIST.put("ErrorEscalationThrower.java#throwEscalation[if (isInterrupting(boundary)) {]|dbService.cancelActivity(scope.getId())", R08);
        // ── TERMINATE: гасится весь инстанс и доводится до конца в этом же хвосте. У…
        WHITELIST.put("EndEventHandler.java#handle[for (Activity active : inScope) {]|dbService.cancelActivity(active.getId())", R09);
        WHITELIST.put("EndEventHandler.java#handle[for (Activity active : inScope) {]|dbService.cancelActivity(scopeActivityId)", R09);
        WHITELIST.put("EndEventHandler.java#handle[if (token == null || token.getScopeActivityId() == null) {]|dbService.cancelActiveActivities(ctx.processInstanceId())", R09);
        // ── прерывающий event-subprocess ЗАМЕЩАЕТ основной поток по BPMN: его запуск…
        WHITELIST.put("EventTrigger.java#triggerEventSubprocess[if (ext.isInterrupting()) {]|dbService.cancelActiveActivities(processInstanceId)", R10);
        // ── scope-контейнер границы: гасятся activity СВОЕГО scope и его ДОЧЕРНИЕ ИН…
        WHITELIST.put("EventTrigger.java#cancelScopeContainer[for (Activity active : inScope) {]|dbService.cancelActivity(active.getId())", R11);
        WHITELIST.put("EventTrigger.java#cancelScopeContainer[for (Activity active : inScope) {]|dbService.cancelActivity(hostActivityId)", R11);
        WHITELIST.put("EventTrigger.java#cancelScopeContainer[for (UUID childInstanceId : dbService.findRunningChildInstanceIds(hostActivityId)) {]|dbService.cancelActiveActivities(childInstanceId)", R11);
        // ── ветка ПРЕРЫВАЮЩЕЙ границы в EventTrigger.fireBoundary: хост (или его MI-…
        WHITELIST.put("EventTrigger.java#fireBoundary[for (Activity instance : ownInstances) {]|dbService.cancelActivity(instance.getId())", R12);
        WHITELIST.put("EventTrigger.java#fireBoundary[} else if (!miHost && isPlainHostOnSharedToken(processInstanceId, tokenId, host)) {]|dbService.cancelActivity(hostActivityId)", R12);
        WHITELIST.put("EventTrigger.java#fireBoundary[} else {]|dbService.cancelActiveActivitiesForToken(tokenId)", R12);
        WHITELIST.put("EventTrigger.java#fireBoundary[} else {]|dbService.cancelActivity(hostActivityId)", R12);
        // ── событийный шлюз: отменяются ПРОИГРАВШИЕ catch-братья, победитель идёт да…
        WHITELIST.put("CompletionService.java#signal[if (isBehindEventBasedGateway(bpmn, bpmnElement)) {]|dbService.cancelActiveActivitiesForToken(tokenId)", R13);
        WHITELIST.put("CompletionService.java#signal[if (variables != null && !variables.isEmpty()) {]|dbService.completeActivity(activityId)", R13);
        // ── это САМА строка срабатывающего join-а: activity join-а закрывается, а пр…
        WHITELIST.put("InclusiveGatewayHandler.java#completeInclusiveJoin[if (token == null) {]|dbService.completeActivity(activityId)", R14);
        WHITELIST.put("InclusiveGatewayHandler.java#handle[} else { #2]|dbService.completeActivity(activityId)", R14);
    }

    @Test
    void everyDeactivationSiteEitherWakesParkedJoinsOrIsWhitelistedWithAReason() throws IOException {
        Map<String, List<Site>> byKey = scanAllDeactivationSites();
        int totalSites = byKey.values().stream().mapToInt(List::size).sum();

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<Site>> e : byKey.entrySet()) {
            Site site = e.getValue().get(0);
            if (site.branchHasResume()) {
                continue;
            }
            String reason = WHITELIST.get(e.getKey());
            if (reason == null || reason.isBlank()) {
                violations.add(e.getKey() + " (" + site.file() + ":" + (site.line() + 1)
                    + ") — нет перепроверки в этой ветке метода " + site.method()
                    + " и нет записи в белом списке с причиной");
            }
        }
        assertThat(violations)
            .as("каждое место деактивации обязано либо будить припаркованные join-ы в своей ветке, "
                + "либо быть в WHITELIST с непустой причиной")
            .isEmpty();

        List<String> stale = WHITELIST.keySet().stream()
            .filter(k -> !byKey.containsKey(k))
            .toList();
        assertThat(stale)
            .as("в белом списке есть записи, которых больше нет на диске — пересмотри список")
            .isEmpty();

        assertThat(WHITELIST.values())
            .as("у каждой записи белого списка должна быть непустая причина")
            .allSatisfy(reason -> assertThat(reason).isNotBlank());

        assertThat(totalSites)
            .as("число мест деактивации изменилось (" + totalSites + " вместо " + EXPECTED_SITE_COUNT
                + "): новое место обязано быть разобрано (перепроверка в его ветке) или внесено в "
                + "белый список с причиной — иначе тест перестанет ловить BLOCKER-4")
            .isEqualTo(EXPECTED_SITE_COUNT);

        assertThat(totalSites)
            .as("сканер мест деактивации обязан что-то находить, а не работать вхолостую")
            .isGreaterThan(50);
    }

    @Test
    void scannerSeesTheProductionTree() throws IOException {
        List<String> files;
        try (Stream<Path> stream = Files.walk(MAIN)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
        }
        assertThat(files)
            .as("сканер должен читать реальное дерево src/main, а не пустоту")
            .hasSizeGreaterThan(100);
    }

    // ── сканер ──────────────────────────────────────────────────────────────────────────────

    private record Site(String file, String method, String branch, int line, String call, boolean branchHasResume) {
    }

    private record Frame(String label, boolean isMethod, int start, String branch) {
    }

    private static final Pattern DEACTIVATION_CALL = Pattern.compile(
        "dbService\\.(cancelActivity|completeActivity|errorActivity|cancelActiveActivities"
        + "|cancelActiveActivitiesForToken)\\(");

    private static final List<String> JAVA_KEYWORDS = List.of(
        "if", "for", "while", "switch", "catch", "else", "do", "try", "new", "return",
        "synchronized", "assert", "throw", "case", "record", "yield", "super", "this",
        "package", "import", "default");

    private Map<String, List<Site>> scanAllDeactivationSites() throws IOException {
        Map<String, List<Site>> byKey = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(MAIN)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (Path file : files) {
            scanFile(file, byKey);
        }
        return byKey;
    }

    private void scanFile(Path file, Map<String, List<Site>> byKey) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        String[] rawLines = raw.split("\n", -1);
        String[] cleanLines = stripLiteralsAndComments(raw).split("\n", -1);
        String fileName = file.getFileName().toString();

        // проход 1: ветки и места деактивации; проход 2: границы всех блоков и признак resume
        List<Frame> frames = collectFrames(cleanLines);
        List<int[]> extents = collectExtents(cleanLines);

        for (int i = 0; i < cleanLines.length; i++) {
            if (!DEACTIVATION_CALL.matcher(cleanLines[i]).find()) {
                continue;
            }
            int frameIndex = innermostFrameAt(frames, i);
            if (frameIndex < 0) {
                continue;
            }
            Frame frame = frames.get(frameIndex);
            String method = enclosingMethod(frames, i);
            String call = rawLines[i].trim();
            if (call.endsWith(";")) {
                call = call.substring(0, call.length() - 1);
            }
            boolean hasResume = false;
            int end = -1;
            for (int[] e : extents) {
                if (e[0] == frame.start()) {
                    end = e[1];
                    break;
                }
            }
            if (end >= 0) {
                String body = String.join("\n", java.util.Arrays.copyOfRange(rawLines, frame.start(), end + 1));
                hasResume = body.contains("resumeParkedInclusiveJoins");
            }
            String branch = frame.branch();
            if (frame.isMethod()) {
                branch = "";
            }
            String key = fileName + "#" + method + "[" + disambiguate(frames, branch, frameIndex) + "]|" + call;
            byKey.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new Site(fileName, method, branch, i, call, hasResume));
        }
    }

    /** Порядковый номер ветки среди веток метода с ОДИНАКОВОЙ строкой открытия (для ключа списка). */
    private String disambiguate(List<Frame> frames, String branch, int frameIndex) {
        if (branch.isEmpty()) {
            return branch;
        }
        String method = enclosingMethod(frames, frames.get(frameIndex).start());
        int ordinal = 0;
        for (int i = 0; i <= frameIndex; i++) {
            Frame f = frames.get(i);
            if (f.isMethod() || f.branch().isEmpty()) {
                continue;
            }
            if (f.branch().equals(branch) && enclosingMethodUpTo(frames, i).equals(method)) {
                ordinal++;
            }
        }
        return ordinal == 1 ? branch : branch + " #" + ordinal;
    }

    private String enclosingMethodUpTo(List<Frame> frames, int index) {
        String method = null;
        for (int i = 0; i <= index; i++) {
            if (frames.get(i).isMethod()) {
                method = frames.get(i).label();
            }
        }
        return method == null ? "<UNRESOLVED>" : method;
    }

    private static String enclosingMethod(List<Frame> frames, int line) {
        String method = null;
        for (Frame f : frames) {
            if (f.start() <= line && f.isMethod()) {
                method = f.label();
            }
        }
        return method == null ? "<UNRESOLVED>" : method;
    }

    private static int innermostFrameAt(List<Frame> frames, int line) {
        int best = -1;
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (f.start() <= line && (best < 0 || f.start() >= frames.get(best).start())) {
                best = i;
            }
        }
        return best;
    }

    /** Все блоки (методы, ветки, лямбды) в порядке открытия. */
    private List<Frame> collectFrames(String[] cleanLines) {
        List<Frame> frames = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame("<file>", false, -1, ""));
        for (int i = 0; i < cleanLines.length; i++) {
            int closes = count(cleanLines[i], '}');
            int opens = count(cleanLines[i], '{');
            for (int c = 0; c < closes; c++) {
                if (stack.size() > 1) {
                    stack.pop();
                }
            }
            if (opens > 0) {
                String name = methodNameAt(cleanLines, i);
                String branch = name == null ? normalize(cleanLines[i].trim()) : "";
                if (branch.length() > 90) {
                    branch = branch.substring(0, 90);
                }
                for (int o = 0; o < opens; o++) {
                    Frame frame = new Frame(name == null ? stack.peek().label() : name,
                        name != null, i, branch);
                    stack.push(frame);
                    frames.add(frame);
                }
            }
        }
        return frames;
    }

    /** Границы блоков (та же нумерация, что collectFrames) — [start, end] на каждый блок. */
    private List<int[]> collectExtents(String[] cleanLines) {
        List<int[]> extents = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(-1);
        for (int i = 0; i < cleanLines.length; i++) {
            int closes = count(cleanLines[i], '}');
            int opens = count(cleanLines[i], '{');
            for (int c = 0; c < closes; c++) {
                int start = stack.isEmpty() ? -1 : stack.pop();
                extents.add(new int[] { start, i });
            }
            for (int o = 0; o < opens; o++) {
                stack.push(i);
            }
        }
        return extents;
    }

    private static int count(String line, char c) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Имя метода, чьё тело открывает скобка в строке {@code i}: последний токен перед первой
     * {@code (} в склеенной сигнатуре (склейка идёт вверх по строкам, заканчивающимся запятой —
     * это параметры многострочной сигнатуры). null для {@code if/for/try/lambda}, присваиваний
     * и прочего — такие блоки наследуют имя внешнего метода.
     */
    private static String methodNameAt(String[] lines, int i) {
        StringBuilder sig = new StringBuilder(lines[i].trim());
        int steps = 0;
        for (int j = i - 1; j >= 0 && steps < 8; j--, steps++) {
            String t = lines[j].trim();
            if (t.isEmpty() || t.contains(";") || t.endsWith("{") || t.endsWith("}")) {
                break;
            }
            if (!t.endsWith(",")) {
                String first = firstToken(t);
                if (JAVA_KEYWORDS.contains(first) || !first.matches("@?\\w+")) {
                    break;
                }
            }
            sig.insert(0, t + " ");
        }
        String signature = sig.toString().trim();
        if (!firstToken(signature).matches("@?\\w+") || !signature.endsWith("{")) {
            return null;
        }
        String body = signature.substring(0, signature.length() - 1);
        int p = body.indexOf('(');
        if (p < 0) {
            return null;
        }
        String head = body.substring(0, p).trim();
        if (head.isEmpty() || head.contains("->") || head.contains("=")) {
            return null;
        }
        String name = lastToken(head);
        if (name.isEmpty() || JAVA_KEYWORDS.contains(name) || !name.matches("\\w+")) {
            return null;
        }
        return name;
    }

    private static String firstToken(String s) {
        String[] parts = s.split("[\\s(]");
        return parts.length == 0 ? "" : parts[0];
    }

    private static String lastToken(String s) {
        String[] parts = s.split("[\\s<>,.\\[\\]]+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    /** Затирает содержимое строковых/символьных литералов и комментариев, сохраняя длину и переводы строк. */
    static String stripLiteralsAndComments(String text) {
        char[] out = text.toCharArray();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                int j = i + 1;
                while (j < n) {
                    if (text.charAt(j) == '\\') {
                        j += 2;
                        continue;
                    }
                    if (text.charAt(j) == quote) {
                        j++;
                        break;
                    }
                    j++;
                }
                blank(out, i, Math.min(j, n));
                i = j;
                continue;
            }
            if (text.startsWith("//", i)) {
                int j = text.indexOf('\n', i);
                j = j < 0 ? n : j;
                blank(out, i, j);
                i = j;
                continue;
            }
            if (text.startsWith("/*", i)) {
                int j = text.indexOf("*/", i);
                j = j < 0 ? n : j + 2;
                blank(out, i, j);
                i = j;
                continue;
            }
            i++;
        }
        return new String(out);
    }

    private static void blank(char[] out, int from, int to) {
        for (int k = from; k < to; k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
    }
}
