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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-15: structure coverage beyond the original 12 mapped types.
 * POF RED first: scriptTask (parsed by the JAXB model, silently dropped by
 * collectProcessNodes) must appear as a node.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class BpmnStructureCoverageTest {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private BpmnStructureService bpmnStructureService;

    private BpmnNode node(List<BpmnNode> nodes, String id) {
        for (BpmnNode n : nodes) {
            if (n.getId().equals(id)) {
                return n;
            }
            for (BpmnNode b : n.getBoundaryEvents()) {
                if (b.getId().equals(id)) {
                    return b;
                }
            }
            if (n.getChildren() != null) {
                BpmnNode nested = node(n.getChildren().getNodes(), id);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @Test
    void getStructure_scriptTask_isMapped() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-structure-coverage.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnProcessStructure structure = bpmnStructureService.getStructure(def.getId()).orElseThrow();

        BpmnNode script = node(structure.getNodes(), "scriptTask1");
        assertThat(script).isNotNull();
        assertThat(script.getType()).isEqualTo("scriptTask");
    }

    @Test
    void getStructure_manualAndBusinessRuleTasks_areMapped() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-structure-coverage.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnProcessStructure structure = bpmnStructureService.getStructure(def.getId()).orElseThrow();

        BpmnNode manual = node(structure.getNodes(), "manualTask1");
        assertThat(manual).isNotNull();
        assertThat(manual.getType()).isEqualTo("manualTask");
        BpmnNode rule = node(structure.getNodes(), "ruleTask1");
        assertThat(rule).isNotNull();
        assertThat(rule.getType()).isEqualTo("businessRuleTask");
    }

    @Test
    void getStructure_inclusiveGateway_isMapped() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-structure-coverage.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnProcessStructure structure = bpmnStructureService.getStructure(def.getId()).orElseThrow();

        BpmnNode gateway = node(structure.getNodes(), "incGateway");
        assertThat(gateway).isNotNull();
        assertThat(gateway.getType()).isEqualTo("inclusiveGateway");
        assertThat(gateway.getProperties().get("defaultFlow")).isEqualTo("flow5");
    }

    @Test
    void getStructure_nestedCallActivityCatchEventAndBoundary_areMapped() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-structure-coverage.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnProcessStructure structure = bpmnStructureService.getStructure(def.getId()).orElseThrow();

        BpmnNode sub = node(structure.getNodes(), "sub1");
        assertThat(sub).isNotNull();
        assertThat(sub.getType()).isEqualTo("subProcess");

        BpmnNode nestedCall = node(structure.getNodes(), "nestedCall");
        assertThat(nestedCall).isNotNull();
        assertThat(nestedCall.getType()).isEqualTo("callActivity");
        List<Map<String, String>> inputs =
            (List<Map<String, String>>) nestedCall.getProperties().get("inputMappings");
        assertThat(inputs).containsExactly(Map.of("source", "=x", "target", "y"));

        BpmnNode nestedCatch = node(structure.getNodes(), "nestedCatch");
        assertThat(nestedCatch).isNotNull();
        assertThat(nestedCatch.getType()).isEqualTo("intermediateCatchEvent");

        BpmnNode nestedBoundary = node(structure.getNodes(), "nestedBoundary");
        assertThat(nestedBoundary).isNotNull();
        assertThat(nestedBoundary.getType()).isEqualTo("boundaryEvent");
    }

    @Test
    void getStructure_transactionAndEventBasedGateway_areMapped() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-structure-coverage.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnProcessStructure structure = bpmnStructureService.getStructure(def.getId()).orElseThrow();

        BpmnNode tx = node(structure.getNodes(), "tx1");
        assertThat(tx).isNotNull();
        assertThat(tx.getType()).isEqualTo("subProcess");
        assertThat(tx.getDocumentation()).isEqualTo("Transactional scope");
        BpmnNode txTask = node(structure.getNodes(), "txTask");
        assertThat(txTask).isNotNull();
        assertThat(txTask.getType()).isEqualTo("serviceTask");

        BpmnNode gateway = node(structure.getNodes(), "eventGateway");
        assertThat(gateway).isNotNull();
        assertThat(gateway.getType()).isEqualTo("eventBasedGateway");
    }

    @Test
    void getStructure_documentation_reachesCatchEventAndSubProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-structure-coverage.bpmn"));
        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        BpmnProcessStructure structure = bpmnStructureService.getStructure(def.getId()).orElseThrow();

        assertThat(node(structure.getNodes(), "nestedCatch").getDocumentation())
            .isEqualTo("Wait for the message");
        assertThat(node(structure.getNodes(), "sub1").getDocumentation())
            .isEqualTo("Nested scope docs");
        assertThat(node(structure.getNodes(), "nestedThrow").getDocumentation())
            .isEqualTo("Fire the signal");
        assertThat(node(structure.getNodes(), "nestedBoundary").getDocumentation())
            .isEqualTo("Ping on stall");
        assertThat(node(structure.getNodes(), "scriptTask1").getDocumentation())
            .isEqualTo("Run the script");
        assertThat(node(structure.getNodes(), "incGateway").getDocumentation())
            .isEqualTo("Either branch");
    }
}
