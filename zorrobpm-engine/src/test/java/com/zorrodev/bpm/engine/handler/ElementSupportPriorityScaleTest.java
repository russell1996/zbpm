package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-QW-6 (NEW3-08): FEEL после WO-ENG-27 отдаёт целые с масштабом
 * ({@code "= 2.50 * 2"} → {@code "5.00"}), а {@code Integer.parseInt} на таком
 * падает: job priority уходил в null / user-task падал в инцидент. Фикс —
 * {@code new BigDecimal(s).intValueExact()}: целые с лишними нулями масштаба
 * парсятся, настоящая дробь по-прежнему явная ошибка (гарантия не меняется).
 *
 * <p>Гоняется через реальный бин ({@code @Autowired ElementSupport}), статический
 * приоритет без переменных — wiring боевой, не стенд.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ElementSupportPriorityScaleTest {

    @Autowired
    private ElementSupport elementSupport;

    private static BpmnElementModel svc(String priority) {
        BpmnElementModel el = new BpmnElementModel();
        el.setId("svc");
        el.setType(BpmnElementType.SERVICE_TASK);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        ServiceTaskExtensionModel svc = new ServiceTaskExtensionModel();
        svc.setPriority(priority);
        ext.setServiceTaskExtension(svc);
        el.setExtensions(ext);
        return el;
    }

    private static BpmnElementModel usr(String priority) {
        BpmnElementModel el = new BpmnElementModel();
        el.setId("usr");
        el.setType(BpmnElementType.USER_TASK);
        BpmnElementExtensionModel ext = new BpmnElementExtensionModel();
        UserTaskExtensionModel u = new UserTaskExtensionModel();
        u.setPriority(priority);
        ext.setUserTaskExtension(u);
        el.setExtensions(ext);
        return el;
    }

    @Test
    void resolvePriority_scaledIntegers_parseToFive() {
        UUID pi = UUID.randomUUID();
        assertThat(elementSupport.resolvePriority(pi, svc("5.00"))).isEqualTo(5);
        assertThat(elementSupport.resolvePriority(pi, svc("5.0"))).isEqualTo(5);
        assertThat(elementSupport.resolvePriority(pi, svc("5"))).isEqualTo(5);
    }

    @Test
    void resolveUserTaskPriority_scaledIntegers_parseToFive() {
        UUID pi = UUID.randomUUID();
        assertThat(elementSupport.resolveUserTaskPriorityOrThrow(pi, usr("5.00"))).isEqualTo(5);
        assertThat(elementSupport.resolveUserTaskPriorityOrThrow(pi, usr("5.0"))).isEqualTo(5);
        assertThat(elementSupport.resolveUserTaskPriorityOrThrow(pi, usr("5"))).isEqualTo(5);
    }

    @Test
    void resolvePriority_fractional_resolvesToNull() {
        // Контракт resolvePriority: битые значения → null, не исключение.
        assertThat(elementSupport.resolvePriority(UUID.randomUUID(), svc("5.50"))).isNull();
    }

    @Test
    void resolveUserTaskPriority_fractional_throwsExplicit() {
        // Контракт OrThrow: битое значение → явный EngineException, не null/default.
        assertThatThrownBy(() -> elementSupport.resolveUserTaskPriorityOrThrow(UUID.randomUUID(), usr("5.50")))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("priorityDefinition");
    }
}
