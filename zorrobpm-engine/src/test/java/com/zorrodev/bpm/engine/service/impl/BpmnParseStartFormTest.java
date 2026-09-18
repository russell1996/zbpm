package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-26, крит. 1: {@code zeebe:formDefinition} разбирается у PLAIN стартового
 * события (formKey/formId/bindingType; versionTag — только в модель, ⛔).
 * formDefinition на message/timer-стартах (триггеры) процессом НЕ подхватывается.
 * Inline-BPMN: новых фикстур-файлов нет, P-35-риска нет.
 */
class BpmnParseStartFormTest {

    private static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_c8sf"
            targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="c8-start-form" name="c8-start-form" isExecutable="true">
            <bpmn:startEvent id="startEvent" name="startEvent">
              <bpmn:extensionElements>
                <zeebe:formDefinition formId="order-start-form" bindingType="deployment" versionTag="v3" />
                <zeebe:properties>
                  <zeebe:property name="formKey" value="legacyStartForm" />
                </zeebe:properties>
              </bpmn:extensionElements>
              <bpmn:outgoing>f1</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:startEvent id="timerStart" name="timerStart">
              <bpmn:extensionElements>
                <zeebe:formDefinition formId="timer-form" />
              </bpmn:extensionElements>
              <bpmn:outgoing>f2</bpmn:outgoing>
              <bpmn:timerEventDefinition><bpmn:timeCycle>R/PT1H</bpmn:timeCycle></bpmn:timerEventDefinition>
            </bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="startEvent" targetRef="endEvent" />
            <bpmn:sequenceFlow id="f2" sourceRef="timerStart" targetRef="endEvent" />
            <bpmn:endEvent id="endEvent" name="endEvent">
              <bpmn:incoming>f1</bpmn:incoming>
              <bpmn:incoming>f2</bpmn:incoming>
            </bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;

    @Test
    void parseStartFormDefinition_plainStartOnly() {
        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(BPMN);

        assertThat(bpmn).isNotNull();
        // plain start: всё трио разобрано; триггерный formDefinition НЕ подхвачен
        assertThat(bpmn.getStartFormId()).isEqualTo("order-start-form");
        assertThat(bpmn.getStartFormBindingType()).isEqualTo("deployment");
        // versionTag — только в модель (⛔ граница: реализация в WO-C8-27)
        assertThat(bpmn.getStartFormVersionTag()).isEqualTo("v3");
        // legacy-скаляр рядом — не затёрт (fallback живёт, приоритет — на resolve)
        assertThat(bpmn.getStartFormKey()).isEqualTo("legacyStartForm");
    }

    @Test
    void parseStartFormDefinition_absent_allNull() {
        BpmnParseService service = new BpmnParseServiceImpl();
        String noForm = BPMN.replace(
            "<zeebe:formDefinition formId=\"order-start-form\" bindingType=\"deployment\" versionTag=\"v3\" />",
            "");
        BpmnProcessDefinitionModel bpmn = service.parse(noForm);

        assertThat(bpmn.getStartFormId()).isNull();
        assertThat(bpmn.getStartFormBindingType()).isNull();
        assertThat(bpmn.getStartFormVersionTag()).isNull();
        assertThat(bpmn.getStartFormKey()).isEqualTo("legacyStartForm");
    }
}
