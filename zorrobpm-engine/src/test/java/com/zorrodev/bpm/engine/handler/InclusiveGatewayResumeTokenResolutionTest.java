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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-C8-35 раунд 4, Решение CTO 1 — токен резюма не может быть {@code null}/устаревшим.
 *
 * <p>Раунд 3-bis передал в {@code resumeParkedInclusiveJoins} токен припаркованной ветви как
 * {@code null} (в arrived-строках {@code parallel_gateways} колонки {@code token} нет). Это уронило
 * ВЕСЬ хвост вызывающего: {@code completeInclusiveJoin → dbService.getToken(null)}, а тот —
 * {@code findToken(id).orElseThrow()} — бросает, рождается инцидент, и прерывающий
 * event-subprocess не стартует вовсе. Тихий висящий join при этом остался висять: сделка обернулась
 * регрессом рабочего пути, и правку пришлось откатить (sha {@code d8b6294f}).
 *
 * <p>Решение 1 CTO: «бери токен ТЕМ ЖЕ способом — а не {@code null}». Разбор показал, что
 * call-site с «настоящим» токеном здесь и не нужен (живой прогон: прерывающий event-subprocess
 * ЗАМЕЩАЕТ отменённый поток, и воскрешать его join нельзя — см.
 * {@code EventSubProcessInclusiveJoinIntegrationTests}), поэтому честный остаток решения — не
 * подбирать токен, а <b>не позволить резюму уронить хвост вызывающего</b>: токен резолвит сам
 * хендлер, и если взять нечего, резюм молча ничего не делает вместо исключения.
 *
 * <p>Тест намеренно бьёт по ПРОД-коду ({@code InclusiveGatewayHandler.resumeParkedInclusiveJoins},
 * не мок): снять этот guard — значит уронить эти тесты (G-N).
 */
@ExtendWith(MockitoExtension.class)
class InclusiveGatewayResumeTokenResolutionTest {

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

    @BeforeEach
    void setUp() {
        ElementSupport elementSupport = new ElementSupport(dbService, scriptService,
            org.mockito.Mockito.mock(FeelBudget.class),
            org.mockito.Mockito.mock(tools.jackson.databind.ObjectMapper.class),
            ZoneId.of("Asia/Almaty"), false);
        handler = new InclusiveGatewayHandler(dbService, flowNavigator, scriptService, elementSupport);

        bpmn = new BpmnProcessDefinitionModel();
        BpmnElementModel join = new BpmnElementModel();
        join.setId("join");
        join.setType(BpmnElementType.INCLUSIVE_GATEWAY);
        join.getIncoming().add("fA");
        join.getIncoming().add("fB");
        join.getOutgoing().add("fOut");
        bpmn.addElement(join);
        BpmnElementModel after = new BpmnElementModel();
        after.setId("after");
        bpmn.addElement(after);
        BpmnFlowModel flow = new BpmnFlowModel();
        flow.setFlowId("fOut");
        flow.setSourceRef("join");
        flow.setTargetRef("after");
        bpmn.addFlow(flow);

        // a parked, READY join: one arrival held open and nobody left who could deliver
        lenient().when(dbService.getGatewaysWithOpenArrivals(any())).thenReturn(Set.of("join"));
        lenient().when(dbService.getActiveActivities(any())).thenReturn(List.of());
        lenient().when(dbService.getParallelGatewayArrivedFlows(any(), eq("join"))).thenReturn(Set.of("fA"));
    }

    private Token token(UUID id, UUID parentId) {
        Token t = new Token();
        t.setId(id);
        t.setParentId(parentId);
        return t;
    }

    @Test
    void resume_nullToken_doesNotThrowAndFiresNothing() {
        // The round-3-bis crash, verbatim: a caller that cannot supply a token must not take the
        // whole tail down with it (there it blocked the interrupting event-subprocess from starting).
        UUID pi = UUID.randomUUID();

        assertThatCode(() -> handler.resumeParkedInclusiveJoins(pi, null, bpmn, executor))
            .as("resume must degrade to 'do nothing', never to an exception in the caller's tail")
            .doesNotThrowAnyException();

        verify(dbService, never()).createActivity(any(), any(), any(BpmnElementModel.class));
        verify(dbService, never()).clearParallelGatewayArrivals(any(), any());
    }

    @Test
    void resume_staleTokenRowDeletedMidFlight_doesNotThrowAndFiresNothing() {
        // Same class of failure without the literal null: a token whose row is gone (retention
        // cleanup / cancel race) — getToken() is findToken().orElseThrow().
        UUID pi = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        when(dbService.findToken(stale)).thenReturn(Optional.empty());

        assertThatCode(() -> handler.resumeParkedInclusiveJoins(pi, stale, bpmn, executor))
            .doesNotThrowAnyException();

        verify(dbService, never()).createActivity(any(), any(), any(BpmnElementModel.class));
    }

    @Test
    void resume_liveToken_stillFiresTheJoinAndCollapsesToItsParent() {
        // The guard must not disable the working path: this is what the five other call sites do,
        // and the collapse (parent, or the token itself when it is a root) must stay byte-identical
        // to the arrival path in completeInclusiveJoin.
        UUID pi = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(dbService.findToken(tokenId)).thenReturn(Optional.of(token(tokenId, parentId)));

        handler.resumeParkedInclusiveJoins(pi, tokenId, bpmn, executor);

        verify(dbService).clearParallelGatewayArrivals(pi, "join");
        ArgumentCaptor<UUID> tokenCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(dbService).createActivity(eq(pi), tokenCaptor.capture(), any(BpmnElementModel.class));
        assertThat(tokenCaptor.getValue())
            .as("the join continues on the token's parent — the same collapse the arrival path uses")
            .isEqualTo(parentId);
        verify(executor).execute(eq(pi), eq(parentId), eq(bpmn), any());
    }
}