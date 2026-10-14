package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.xml.BpmnMultiInstanceModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-50: вложенный {@code <bpmn:multiInstanceLoopCharacteristics>} (внутри
 * {@code <bpmn:extensionElements>} хоста) С дочерним {@code <bpmn:completionCondition
 * xsi:type="bpmn:tFormalExpression">} молча теряется целиком —
 * {@code readSubProcessMultiInstance} возвращает null, и подпроцесс идёт как одиночный.
 *
 * <p>Без {@code completionCondition} та же форма разбирается (поведение v44), со
 * стандартным прямым потомком — тоже. Единственное отличие сломанного случая —
 * атрибут {@code xsi:type} на дочернем элементе при фрагментном unmarshal без
 * {@code xmlns:xsi} в обёртке.
 */
class MiNestedCompletionConditionParseTest {

    private static String read(String file) throws Exception {
        return Files.readString(Path.of("src/test/files/" + file));
    }

    @Test
    void nestedMi_withCompletionCondition_bindsModelAndCondition() throws Exception {
        String raw = read("test-c850-consumer-v51.bpmn");

        BpmnMultiInstanceModel mi =
            BpmnParseServiceImpl.readSubProcessMultiInstance(raw, "subWorkTaskStage");

        assertThat(mi)
            .as("v51 nested MI with completionCondition must bind (consumer defect: whole MI dropped)")
            .isNotNull();
        assertThat(mi.getCompletionCondition())
            .as("completionCondition text must be parsed like the standard form")
            .contains("REJECTED");
        assertThat(mi.getExtensionElements()).as("zeebe:loopCharacteristics must be bound").isNotNull();
        assertThat(mi.getExtensionElements().getLoopCharacteristics().getInputCollection())
            .isEqualTo("=openActivities");
    }

    @Test
    void nestedMi_withoutCompletionCondition_stillBinds() throws Exception {
        String raw = read("test-c840-approval-route-v2.bpmn");

        BpmnMultiInstanceModel mi =
            BpmnParseServiceImpl.readSubProcessMultiInstance(raw, "subWorkTaskStage");

        assertThat(mi).as("v44 behaviour (no completionCondition) must keep binding").isNotNull();
    }
}
