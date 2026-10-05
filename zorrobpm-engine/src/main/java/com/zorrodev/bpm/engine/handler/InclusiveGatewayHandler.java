package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handler for INCLUSIVE_GATEWAY elements.
 *
 * <p>WO-C8-35 (CR-09) раунд 3: готовность join'а — единое правило
 * {@link ElementSupport#isInclusiveJoinReady}, без статического счётчика ветвей; см. блок
 * комментария в {@code ElementSupport}. Фанаут сплита — двухфазный, а завершение join'а и
 * перепроверка припаркованных join'ов живут в {@link #completeInclusiveJoin} /
 * {@link #resumeParkedInclusiveJoins}: хендлер и резюм не должны разойтись.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InclusiveGatewayHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final FlowNavigator flowNavigator;
    private final ScriptService scriptService;
    private final ElementSupport elementSupport;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.INCLUSIVE_GATEWAY; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();
        TokenExecutor executor = ctx.executor();

        List<String> incomings = bpmnElement.getIncoming();
        List<String> outgoings = bpmnElement.getOutgoing();

        if (outgoings.size() > 1 && incomings.size() == 1) {
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

            String defaultFlowId = Optional.ofNullable(bpmnElement.getExtensions())
                .map(BpmnElementExtensionModel::getExclusiveGatewayExtension)
                .map(ExclusiveGatewayExtensionModel::getDefaultFlowId)
                .orElse(null);

            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            List<String> activated = new ArrayList<>();
            for (String outgoing : outgoings) {
                if (outgoing.equals(defaultFlowId)) {
                    continue;
                }
                if (isFlowActive(bpmn, outgoing, variables)) {
                    activated.add(outgoing);
                }
            }
            if (activated.isEmpty() && defaultFlowId != null) {
                activated.add(defaultFlowId);
            }

            log.info("{}/{}: Entering and completing {}: {}/{} (activating {} of {} branches)", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(), activated.size(), outgoings.size());

            Token newToken = dbService.createToken(tokenId);
            UUID newTokenId = newToken.getId();
            // WO-ENG-1 (durable): set pending branch count before any branch executes.
            // Use activated.size() (only condition-passing branches), not outgoings.size().
            dbService.setPendingBranches(newTokenId, activated.size());

            // ── Фаза 1: ВСЕ активированные потоки, до запуска любой цели ──────────────────
            // processFlow пишет arrived-строку у join'а, поэтому ветвь «существует» с момента
            // диспетчеризации, а не с момента запуска её первого элемента. Счётчик ветвей
            // снят (решение CTO на M1+M2), и это единственный способ, которым join узнаёт, что
            // его волна уже диспетчеризована: без двухфазности первая же ветвь pass-through
            // фанаута увидела бы пустую вселенную «живых» и сработала бы на первом приходе
            // (доказано RED'ом — join проходил дважды, taskAfter создавался дважды).
            for (String outgoing : activated) {
                flowNavigator.processFlow(processInstanceId, newTokenId, outgoing, false, null);
            }

            // ── Фаза 2: запуск целей ──────────────────────────────────────────────────────
            // Активность СПЛИТА остаётся живой весь фанаут (раньше она завершалась до ветвей).
            // Пока она жива, canReach(split, J) истинна, поэтому join не срабатывает на
            // первой ветви и ждёт обе. Завершается в finally: сплит — это деактивация, а на
            // деактивации перепроверяются припаркованные join'ы (ШAГ 3, B2).
            try {
                for (String outgoing : activated) {
                    BpmnFlowModel flow = bpmn.getFlow(outgoing);
                    BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
                    executor.execute(processInstanceId, newTokenId, bpmn, target);
                }
            } finally {
                dbService.completeActivity(activityId);
                resumeParkedInclusiveJoins(processInstanceId, newTokenId, bpmn, executor);
            }
        } else if (incomings.size() > 1) {
            // WO-C8-35 (CR-09) раунд 3: единственное правило для всех inclusive-join.
            // Статического счётчика больше нет — он писался только inclusive-сплитом (откуда
            // вечное ожидание), вслепую (UK-нарушение на двух сплитах в один join) и мог
            // уехать на шлюз, достигаемый 2 из 3 ветвей (частичная конвергенция).
            if (elementSupport.isInclusiveJoinReady(processInstanceId, bpmn, bpmnElement)) {
                completeInclusiveJoin(processInstanceId, bpmn, bpmnElement, tokenId, executor, "arrival");
            } else {
                Set<String> arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, bpmnElement.getId());
                log.info("{}/{}: Inclusive gateway join not ready yet {}: {} of {} branches arrived"
                        + " (no static counter — someone in the instance can still deliver a branch)",
                    processInstanceId, tokenId, bpmnElement.getId(), arrived.size(),
                    incomings == null ? 0 : incomings.size());
            }
        } else {
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());
            flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, bpmnElement, executor);
        }
    }

    /**
     * WO-C8-35 (CR-09, ШАГ 1/3): единственная точка завершения inclusive-join'а — и приход по
     * ветви, и перепроверка припаркованного join'а после смерти последнего возможного доставщика
     * идут СЮДА. Две копии этой логики однажды разошлись бы снова (ровно как это случилось с
     * {@code resumeParkedCompensationThrowers} до C8-34), поэтому резюм не дублирует её.
     *
     * @param reason {@code arrival} (ветвь пришла) или {@code resume} (перепроверка после
     *               деактивации) — только для лога
     */
    public void completeInclusiveJoin(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel bpmnElement, UUID tokenId, TokenExecutor executor, String reason) {
        Set<String> arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, bpmnElement.getId());
        dbService.clearParallelGatewayArrivals(processInstanceId, bpmnElement.getId());
        // Раунд 4 (Решение 1): getToken() — это findToken().orElseThrow(). Строка токена могла
        // исчезнуть между резолвом в резюме и здесь (retention-cleanup / гонка отмены), и бросок
        // из join'а уронил бы хвост вызывающего на ровном месте. Отсутствующий токен = нечем
        // продолжать — WARN и выход, arrivals уже очищены, повторного зависания не будет.
        Token token = tokenId == null ? null : dbService.findToken(tokenId).orElse(null);
        if (token == null) {
            log.warn("{}/{}: inclusive join {} not completed — its continuation token is gone (requested {})",
                    processInstanceId, tokenId, bpmnElement.getId(), tokenId);
            return;
        }
        // WO-DIFF-4: same null-parent guard as ParallelGatewayHandler — a
        // non-interrupting boundary fork leaves the host branch on the (possibly
        // ROOT) host token; collapsing to a null parent would continue on null.
        UUID oldTokenId = token.getParentId() != null ? token.getParentId() : tokenId;
        UUID activityId = dbService.createActivity(processInstanceId, oldTokenId, bpmnElement);
        dbService.completeActivity(activityId);
        log.info("{}/{}: Entering and completing {}: {}/{} (all deliverable branches arrived: {} of {} — {})",
                processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId(),
                arrived.size(), bpmnElement.getIncoming() == null ? 0 : bpmnElement.getIncoming().size(), reason);
        for (String outgoing : bpmnElement.getOutgoing()) {
            flowNavigator.processFlow(processInstanceId, oldTokenId, outgoing, false, null);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
            executor.execute(processInstanceId, oldTokenId, bpmn, target);
        }
    }

    /**
     * WO-C8-35 (CR-09, ШАГ 3, BLOCKER-2/B2): перепроверка ПРИПАРКОВАННЫХ inclusive-join'ов
     * инстанса после того, как умер последний возможный доставщик.
     *
     * <p>Join перепроверяется только когда в него ПРИХОДИТ ветвь. Если последний возможный
     * доставщик умер в другом месте — XOR ушёл в другую ветку/в конец, отмена, terminate,
     * boundary-fire — припаркованный токен не просыпался никогда: инстанс висел RUNNING
     * без инцидента (BLOCKER-2 red-team). Здесь та же схема, что у
     * {@code CompletionService.resumeParkedCompensationThrowers}: идём по завершению
     * (deactivation) и снимаем «припаркованность», если доставлять больше некому.
     *
     * <p>Итерация до ФИКСАПА, а не один проход: срабатывание одного join'а может открыть
     * arrived-строку у другого (join → join), который тоже должен быть проверен в этом же проходе.
     * Уже сработавшие в этом проходе исключаются — повторное срабатывание того же join'а было бы
     * вторым проходом по уже сработавшей волне.
     *
     * @param tokenId токен, на котором продолжается сработавший join (схлопывается на родителя
     *               тем же правилом, что и в пути прихода). Резолвит САМ хендлер: если токена нет
     *               или его строка уже удалена, резюм ничего не делает (см. блок про токен ниже)
     */
    public void resumeParkedInclusiveJoins(UUID processInstanceId, UUID tokenId,
            BpmnProcessDefinitionModel bpmn, TokenExecutor executor) {
        // WO-C8-35 раунд 4 (Решение 1): «бери токен ТЕМ ЖЕ способом — а не null». Раунд 3-bis
        // передал сюда null (токена припаркованной ветви взять негде), и это уронило ВЕСЬ хвост
        // вызывающего: getToken — это findToken(id).orElseThrow(), плюс NPE на getParentId(),
        // рождался инцидент, и прерывающий event-subprocess не стартовал вовсе. Разбор показал,
        // что call-site с настоящим токеном здесь и не нужен (живой прогон: прерывающий
        // event-subprocess ЗАМЕЩАЕТ отменённый поток — EventSubProcessInclusiveJoinIntegrationTests),
        // поэтому честный остаток решения — не подбирать токен, а не дать резюму уронить чужой
        // хвост: токен резолвится здесь, и взять нечего → WARN и ничего.
        Token resumeToken = tokenId == null ? null : dbService.findToken(tokenId).orElse(null);
        if (resumeToken == null) {
            log.warn("{}/{}: inclusive-join resume skipped — no usable continuation token (requested {})",
                    processInstanceId, tokenId, tokenId);
            return;
        }
        Set<String> firedInPass = new HashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (String gatewayId : dbService.getGatewaysWithOpenArrivals(processInstanceId)) {
                if (firedInPass.contains(gatewayId)) {
                    continue;
                }
                BpmnElementModel join = bpmn.getElement(gatewayId);
                if (join == null || join.getType() != BpmnElementType.INCLUSIVE_GATEWAY
                        || join.getIncoming() == null || join.getIncoming().size() <= 1) {
                    continue;
                }
                if (!elementSupport.isInclusiveJoinReady(processInstanceId, bpmn, join)) {
                    continue;
                }
                firedInPass.add(gatewayId);
                progress = true;
                log.info("{}/{}: Inclusive gateway {} resumed — nobody in the instance can deliver a branch any more",
                        processInstanceId, tokenId, gatewayId);
                completeInclusiveJoin(processInstanceId, bpmn, join, tokenId, executor, "resume");
            }
        }
    }

    private boolean isFlowActive(BpmnProcessDefinitionModel bpmn, String flowId, List<ProcessVariable> variables) {
        BpmnFlowModel flow = bpmn.getFlow(flowId);
        String expression = Optional.ofNullable(flow)
            .map(BpmnFlowModel::getConditionExpression)
            .map(BpmnConditionExpressionModel::getExpression)
            .filter(str -> !str.isEmpty())
            .map(str -> str.substring(1))
            .orElse(null);
        if (expression == null) {
            return true;
        }
        Boolean test = (Boolean) scriptService.evaluateScript(expression, variables);
        return Boolean.TRUE.equals(test);
    }

}
