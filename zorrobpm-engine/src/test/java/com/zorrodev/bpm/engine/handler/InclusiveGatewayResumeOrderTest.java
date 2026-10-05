package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FeelBudget;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WO-C8-35 раунд 5, ШАГ 3 (minor red-team раунда 3): порядок срабатывания нескольких
 * припаркованных inclusive-join'ов не должен определяться {@code HashSet}.
 *
 * <p>Находка: слой чтения отдавал {@code HashSet}, и {@code resumeParkedInclusiveJoins} итерировал
 * его напрямую — то есть при двух и более припаркованных join'ах порядок, в котором они
 * срабатывают, задавался хеш-функцией JVM, а не моделью процесса. Само по себе это уже делало
 * логи невоспроизводимыми; при потере токна посреди прохода от порядка зависело и то, КАКОЙ join
 * молча потеряется.
 *
 * <p>Тест бьёт по ПРОД-коду ({@code InclusiveGatewayHandler.resumeParkedInclusiveJoins}, не мок):
 * возврат итерации по сырому множеству роняет его (G-N). Порядок в ассерте — возрастание
 * element id, и он заведомо НЕ совпадает с порядком {@code HashSet} (проверено в тесте
 * отдельным ассертом, чтобы мутация была красной гарантированно, а не по вкусу JVM).
 */
@ExtendWith(MockitoExtension.class)
class InclusiveGatewayResumeOrderTest {

    @Mock
    private DBService dbService;
    @Mock
    private FlowNavigator flowNavigator;
    @Mock
    private ScriptService scriptService;
    @Mock
    private TokenExecutor executor;

    private InclusiveGatewayHandler handler;
    private BpmnProcessDefinitionModel bpmn;
    private final UUID pi = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ElementSupport elementSupport = new ElementSupport(dbService, scriptService,
            org.mockito.Mockito.mock(FeelBudget.class),
            org.mockito.Mockito.mock(tools.jackson.databind.ObjectMapper.class),
            ZoneId.of("Asia/Almaty"), false);
        handler = new InclusiveGatewayHandler(dbService, flowNavigator, scriptService, elementSupport);
        bpmn = new BpmnProcessDefinitionModel();
        addJoin("joinA");
        addJoin("joinB");
        addJoin("joinC");
        Token token = new Token();
        token.setId(tokenId);
        token.setParentId(UUID.randomUUID());
        lenient().when(dbService.findToken(eq(tokenId))).thenReturn(java.util.Optional.of(token));
        lenient().when(dbService.getActiveActivities(any())).thenReturn(List.of());
    }

    private void addJoin(String id) {
        BpmnElementModel join = new BpmnElementModel();
        join.setId(id);
        join.setType(BpmnElementType.INCLUSIVE_GATEWAY);
        join.getIncoming().add("fA");
        join.getIncoming().add("fB");
        join.getOutgoing().add("fOut" + id);
        bpmn.addElement(join);
        BpmnElementModel after = new BpmnElementModel();
        after.setId("after" + id);
        bpmn.addElement(after);
        BpmnFlowModel flow = new BpmnFlowModel();
        flow.setFlowId("fOut" + id);
        flow.setSourceRef(id);
        flow.setTargetRef("after" + id);
        bpmn.addFlow(flow);
    }

    @Test
    void resume_firesParkedJoinsInElementIdOrder_notInHashSetOrder() {
        // Слой чтения отдаёт набор в порядке HashSet — так он и отдавал до раунда 5.
        HashSet<String> asHashSet = new HashSet<>(List.of("joinA", "joinB", "joinC"));
        assertThat(new ArrayList<>(asHashSet))
            .as("предусловие мутации: порядок HashSet здесь ЗАВЕДОМО не алфавитный, иначе тест "
                + "не смог бы отличить детерминированный порядок от хеш-порядка")
            .isNotEqualTo(List.of("joinA", "joinB", "joinC"));

        when(dbService.getGatewaysWithOpenArrivals(any())).thenReturn(asHashSet);
        for (String id : List.of("joinA", "joinB", "joinC")) {
            lenient().when(dbService.getParallelGatewayArrivedFlows(any(), eq(id))).thenReturn(java.util.Set.of("fA"));
        }

        List<String> fired = new ArrayList<>();
        lenient().when(dbService.createActivity(any(), any(), any(BpmnElementModel.class)))
            .thenAnswer(inv -> {
                fired.add(inv.getArgument(2, BpmnElementModel.class).getId());
                return UUID.randomUUID();
            });

        handler.resumeParkedInclusiveJoins(pi, tokenId, bpmn, executor);

        assertThat(fired)
            .as("порядок срабатывания припаркованных join'ов задаётся моделью (element id), "
                + "а не хеш-порядком множества")
            .containsExactly("joinA", "joinB", "joinC");
    }

    @Test
    void readLayerReturnsGatewaysSortedById_notInHashSetOrder() {
        // Второй слой того же требования: отсортированный набор даёт сам слой чтения
        // (ParallelGatewayDbOperationsImpl.getGatewaysWithOpenArrivals), иначе детерминизм держался
        // бы только на вызывающей стороне.
        var repo = org.mockito.Mockito.mock(com.zorrodev.bpm.engine.repository.ParallelGatewayRepository.class);
        var dbOps = new com.zorrodev.bpm.engine.service.db.ParallelGatewayDbOperationsImpl(
            repo, org.mockito.Mockito.mock(com.zorrodev.bpm.engine.repository.TokenRepository.class));
        when(repo.findGatewayIdsWithOpenArrivals(any())).thenReturn(List.of("joinC", "joinA", "joinB"));

        assertThat(new ArrayList<>(dbOps.getGatewaysWithOpenArrivals(pi)))
            .as("слой чтения обязан отдавать припаркованные join'ы в порядке element id")
            .containsExactly("joinA", "joinB", "joinC");
    }
}