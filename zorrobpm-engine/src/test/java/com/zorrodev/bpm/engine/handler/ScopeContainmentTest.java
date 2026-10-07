package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-38: юнит-тесты лексического containment'а ({@code ScopeContainment}) —
 * чистые функции модели, без БД.
 *
 * <p>Мутационная граница: убери ESP-исключение из
 * {@code inclusiveGatewayIdsInsideScope} (считай join'ы и под ESP) — тест
 * {@code joinsUnderNestedEsp_areExcluded} краснеет; убери null-guard'ы —
 * краснеют {@code nullInputs_returnEmpty}/{@code unknownScope_returnsEmpty}.
 */
class ScopeContainmentTest {

    private final ScopeContainment containment = new ScopeContainment();

    private BpmnProcessDefinitionModel model(BpmnElementModel... elements) {
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        for (BpmnElementModel e : elements) {
            bpmn.addElement(e);
        }
        return bpmn;
    }

    private BpmnElementModel el(String id, BpmnElementType type, String parent) {
        BpmnElementModel e = new BpmnElementModel();
        e.setId(id);
        e.setType(type);
        e.setParentContainerElementId(parent);
        return e;
    }

    @Test
    void joinsDirectlyInsideScope_areListed() {
        BpmnProcessDefinitionModel bpmn = model(
            el("sub", BpmnElementType.SUB_PROCESS, null),
            el("joinIn", BpmnElementType.INCLUSIVE_GATEWAY, "sub"),
            el("joinOut", BpmnElementType.INCLUSIVE_GATEWAY, null));

        assertThat(containment.inclusiveGatewayIdsInsideScope(bpmn, "sub"))
            .containsExactly("joinIn");
    }

    @Test
    void joinsInNestedSubprocess_areListed() {
        BpmnProcessDefinitionModel bpmn = model(
            el("outer", BpmnElementType.SUB_PROCESS, null),
            el("inner", BpmnElementType.SUB_PROCESS, "outer"),
            el("deepJoin", BpmnElementType.INCLUSIVE_GATEWAY, "inner"));

        assertThat(containment.inclusiveGatewayIdsInsideScope(bpmn, "outer"))
            .containsExactly("deepJoin");
        assertThat(containment.inclusiveGatewayIdsInsideScope(bpmn, "inner"))
            .containsExactly("deepJoin");
    }

    @Test
    void joinsUnderNestedEsp_areExcluded() {
        // Join внутри вложенного ESP имеет независимый жизненный цикл (свой scope-
        // инстанс, scope-confined отмена его строк не трогает) — чистка scope НЕ
        // должна его касаться, иначе strand ITS ветвей.
        BpmnProcessDefinitionModel bpmn = model(
            el("sub", BpmnElementType.SUB_PROCESS, null),
            el("esp", BpmnElementType.EVENT_SUB_PROCESS, "sub"),
            el("espJoin", BpmnElementType.INCLUSIVE_GATEWAY, "esp"),
            el("plainJoin", BpmnElementType.INCLUSIVE_GATEWAY, "sub"));

        assertThat(containment.inclusiveGatewayIdsInsideScope(bpmn, "sub"))
            .containsExactly("plainJoin");
    }

    @Test
    void nonJoinElements_areNeverListed() {
        BpmnProcessDefinitionModel bpmn = model(
            el("sub", BpmnElementType.SUB_PROCESS, null),
            el("task", BpmnElementType.USER_TASK, "sub"));

        assertThat(containment.inclusiveGatewayIdsInsideScope(bpmn, "sub")).isEmpty();
    }

    @Test
    void nullInputs_returnEmpty() {
        assertThat(containment.inclusiveGatewayIdsInsideScope(null, "sub")).isEmpty();
        assertThat(containment.inclusiveGatewayIdsInsideScope(model(), null)).isEmpty();
        assertThat(containment.innermostScopeForEventSubprocess(null, "esp")).isNull();
        assertThat(containment.innermostScopeForEventSubprocess(model(), null)).isNull();
    }

    @Test
    void unknownScope_returnsEmpty() {
        BpmnProcessDefinitionModel bpmn = model(
            el("join", BpmnElementType.INCLUSIVE_GATEWAY, null));

        assertThat(containment.inclusiveGatewayIdsInsideScope(bpmn, "noSuchScope")).isEmpty();
    }

    @Test
    void nestedEsp_resolvesItsParentScope() {
        BpmnProcessDefinitionModel bpmn = model(
            el("sub", BpmnElementType.SUB_PROCESS, null),
            el("esp", BpmnElementType.EVENT_SUB_PROCESS, "sub"));

        assertThat(containment.innermostScopeForEventSubprocess(bpmn, "esp"))
            .isEqualTo("sub");
    }

    @Test
    void topLevelEsp_hasNoParentScope() {
        BpmnProcessDefinitionModel bpmn = model(
            el("esp", BpmnElementType.EVENT_SUB_PROCESS, null));

        assertThat(containment.innermostScopeForEventSubprocess(bpmn, "esp")).isNull();
    }

    @Test
    void espInsideEsp_fallsBackToWholeInstance() {
        // ESP-в-ESP: экзотика без фикстуры — честный whole-instance fallback,
        // не выдуманный scope.
        BpmnProcessDefinitionModel bpmn = model(
            el("outerEsp", BpmnElementType.EVENT_SUB_PROCESS, null),
            el("innerEsp", BpmnElementType.EVENT_SUB_PROCESS, "outerEsp"));

        assertThat(containment.innermostScopeForEventSubprocess(bpmn, "innerEsp")).isNull();
    }

    @Test
    void result_isDeterministicallySorted() {
        BpmnProcessDefinitionModel bpmn = model(
            el("sub", BpmnElementType.SUB_PROCESS, null),
            el("joinB", BpmnElementType.INCLUSIVE_GATEWAY, "sub"),
            el("joinA", BpmnElementType.INCLUSIVE_GATEWAY, "sub"));

        List<String> result = containment.inclusiveGatewayIdsInsideScope(bpmn, "sub");
        assertThat(result).containsExactly("joinA", "joinB");
    }
}
