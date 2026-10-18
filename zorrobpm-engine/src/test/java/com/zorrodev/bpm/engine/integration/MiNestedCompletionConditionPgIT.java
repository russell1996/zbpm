package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WO-C8-50 на живом PostgreSQL (V11/P-17: H2 ≠ PG): деплой XML v51 консьюмера
 * распознаёт вложенный MI с {@code completionCondition}, а вложенный MI на
 * неподдерживаемом хосте отклоняется 400 — через настоящий прод-путь деплоя.
 */
@Tag("pg")
class MiNestedCompletionConditionPgIT extends PostgresIT {

    @Autowired private BpmnParseService bpmnParseService;
    @Autowired private ProcessDefinitionService processDefinitionService;

    @Test
    void consumerV51_deploysWithMiRecognised() throws Exception {
        String bpmn = Files.readString(Path.of("src/test/files/test-c850-consumer-v51.bpmn"));

        BpmnProcessDefinitionModel model = bpmnParseService.parse(bpmn);
        assertThat(model.getElements().stream()
            .filter(e -> "subWorkTaskStage".equals(e.getId()))
            .findFirst()
            .map(e -> e.getExtensions() != null && e.getExtensions().getMultiInstanceExtension() != null)
            .orElseThrow())
            .as("v51 subWorkTaskStage must be multi-instance (PostgreSQL)")
            .isTrue();

        assertThat(processDefinitionService.addProcessDefinition(bpmn))
            .as("v51 must deploy (PostgreSQL)")
            .isNotNull();
    }

    @Test
    void nestedMiOnTransaction_deployRefusedWithShapeCode() {
        String bpmn = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                              id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="c850-pg-txn-REPLACEME" name="p" isExecutable="true">
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="loopHost" />
                <bpmn:transaction id="loopHost" name="loopHost">
                  <bpmn:extensionElements>
                    <bpmn:multiInstanceLoopCharacteristics>
                      <bpmn:extensionElements>
                        <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
                      </bpmn:extensionElements>
                      <bpmn:completionCondition xsi:type="bpmn:tFormalExpression">false</bpmn:completionCondition>
                    </bpmn:multiInstanceLoopCharacteristics>
                  </bpmn:extensionElements>
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:transaction>
                <bpmn:sequenceFlow id="f2" sourceRef="loopHost" targetRef="e" />
                <bpmn:endEvent id="e"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """.replace("REPLACEME", java.util.UUID.randomUUID().toString().substring(0, 8));

        ApiException refusal = (ApiException) catchThrowable(
            () -> processDefinitionService.addProcessDefinition(bpmn));

        assertThat(refusal).as("nested MI on transaction must be refused (PostgreSQL)").isNotNull();
        assertThat(refusal.getStatus().value()).isEqualTo(400);
        assertThat(refusal.getCode()).isEqualTo("UNSUPPORTED_MULTI_INSTANCE_SHAPE");
    }
}
