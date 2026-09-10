package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.BpmnNode;
import com.zorrodev.bpm.contract.model.BpmnProcessStructure;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.BpmnStructureService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class BpmnStructureIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private BpmnStructureService bpmnStructureService;

    private BpmnNode node(List<BpmnNode> nodes, String id) {
        return nodes.stream().filter(n -> n.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void getStructure_returnsNestedTreeWithSubProcessAndBoundary() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-error-boundary.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnProcessStructure structure = bpmnStructureService.getStructure(def.getId()).orElseThrow();

        assertThat(structure.getId()).isEqualTo(def.getId());
        assertThat(structure.getKey()).isEqualTo("test-error-boundary");
        assertThat(structure.getName()).isEqualTo("test-error-boundary");

        // top-level nodes: start, sub-process, two end events — boundary is NOT a top-level node
        assertThat(structure.getNodes()).extracting(BpmnNode::getId)
            .contains("startEvent", "sub1", "endEvent", "boundaryEnd")
            .doesNotContain("errBoundary");
        assertThat(structure.getFlows()).extracting(f -> f.getId()).contains("flow1", "flow2", "flowB");

        // sub-process body is nested under children
        BpmnNode sub = node(structure.getNodes(), "sub1");
        assertThat(sub.getType()).isEqualTo("subProcess");
        assertThat(sub.getChildren()).isNotNull();
        assertThat(sub.getChildren().getNodes()).extracting(BpmnNode::getId).contains("subStart", "subErrEnd");
        assertThat(sub.getChildren().getFlows()).extracting(f -> f.getId()).contains("subFlow1");

        // nested error end event carries the resolved error code
        BpmnNode subErrEnd = node(sub.getChildren().getNodes(), "subErrEnd");
        assertThat(subErrEnd.getEventDefinition()).isEqualTo("error");
        assertThat(subErrEnd.getProperties()).containsEntry("errorCode", "E-1");

        // the boundary event is attached to its host sub-process, with resolved details
        assertThat(sub.getBoundaryEvents()).hasSize(1);
        BpmnNode boundary = sub.getBoundaryEvents().get(0);
        assertThat(boundary.getId()).isEqualTo("errBoundary");
        assertThat(boundary.getType()).isEqualTo("boundaryEvent");
        assertThat(boundary.getEventDefinition()).isEqualTo("error");
        assertThat(boundary.getProperties())
            .containsEntry("errorCode", "E-1")
            .containsEntry("attachedToRef", "sub1")
            .containsEntry("cancelActivity", true);
    }

    @Test
    void getStructure_returnsEmptyForUnknownDefinition() {
        assertThat(bpmnStructureService.getStructure(UUID.randomUUID())).isEmpty();
    }

    // --- WO-ENG-14: static zeebe:ioMapping declaration ---

    @Test
    void getStructure_serviceTaskWithIoMapping_exposesInputAndOutputMappings() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-io-mapping-visibility.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnNode mapped = node(bpmnStructureService.getStructure(def.getId()).orElseThrow().getNodes(), "mappedTask");

        assertThat(mapped.getType()).isEqualTo("serviceTask");
        assertThat(mapped.getProperties()).containsKey("inputMappings");
        assertThat(mapped.getProperties()).containsKey("outputMappings");
        List<Map<String, String>> inputs = (List<Map<String, String>>) mapped.getProperties().get("inputMappings");
        assertThat(inputs).containsExactly(Map.of("source", "=orderId", "target", "orderId"));
        List<Map<String, String>> outputs = (List<Map<String, String>>) mapped.getProperties().get("outputMappings");
        assertThat(outputs).containsExactly(Map.of("source", "=result", "target", "orderResult"));
    }

    @Test
    void getStructure_callActivityWithIoMapping_exposesInputMappings() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-io-mapping-visibility.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnNode call = node(bpmnStructureService.getStructure(def.getId()).orElseThrow().getNodes(), "callChild");

        assertThat(call.getType()).isEqualTo("callActivity");
        assertThat(call.getProperties()).containsKey("calledProcessId");
        List<Map<String, String>> inputs = (List<Map<String, String>>) call.getProperties().get("inputMappings");
        assertThat(inputs).containsExactly(Map.of("source", "=parentVar", "target", "childVar"));
        assertThat(call.getProperties()).doesNotContainKey("outputMappings");
    }

    @Test
    void getStructure_userTaskWithIoMapping_exposesInputAndOutputMappings() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-io-mapping-visibility.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnNode user = node(bpmnStructureService.getStructure(def.getId()).orElseThrow().getNodes(), "mappedUser");

        assertThat(user.getType()).isEqualTo("userTask");
        List<Map<String, String>> inputs = (List<Map<String, String>>) user.getProperties().get("inputMappings");
        assertThat(inputs).containsExactly(Map.of("source", "=claimId", "target", "claimId"));
        List<Map<String, String>> outputs = (List<Map<String, String>>) user.getProperties().get("outputMappings");
        assertThat(outputs).containsExactly(Map.of("source", "=approved", "target", "approved"));
    }

    @Test
    void getStructure_elementWithoutIoMapping_hasNoMappingKeys() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-io-mapping-visibility.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnNode plain = node(bpmnStructureService.getStructure(def.getId()).orElseThrow().getNodes(), "plainTask");

        assertThat(plain.getType()).isEqualTo("serviceTask");
        assertThat(plain.getProperties()).containsKey("job");
        assertThat(plain.getProperties()).doesNotContainKey("inputMappings");
        assertThat(plain.getProperties()).doesNotContainKey("outputMappings");
    }
}
