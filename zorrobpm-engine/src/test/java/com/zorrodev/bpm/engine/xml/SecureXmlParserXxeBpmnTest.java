package com.zorrodev.bpm.engine.xml;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.xml.BpmnDefinitionsModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-SEC-20: XXE protection for BPMN parsing.
 * Verifies that DOCTYPE with external entity declarations are rejected.
 */
class SecureXmlParserXxeBpmnTest {

    private static final String XXE_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE foo [
          <!ENTITY xxe SYSTEM "file:///etc/passwd">
        ]>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     id="Definitions_xxe" targetNamespace="http://bpmn.io/schema/bpmn">
          <process id="proc" isExecutable="true">
            <startEvent id="start">&xxe;</startEvent>
          </process>
        </definitions>
        """;

    @Test
    void xxePayload_isRejected() {
        assertThatThrownBy(() -> SecureXmlParser.unmarshal(XXE_BPMN, BpmnDefinitionsModel.class))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("DOCTYPE");
    }

    @Test
    void realBpmnFile_parsesSuccessfully() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test1.bpmn"));
        BpmnDefinitionsModel result = SecureXmlParser.unmarshal(bpmn, BpmnDefinitionsModel.class);
        assertThat(result).isNotNull();
        assertThat(result.getProcess()).isNotNull();
        assertThat(result.getProcess().getId()).isNotBlank();
    }

    @Test
    void xxePayload_doesNotLeakFileContent() {
        try {
            BpmnDefinitionsModel result = SecureXmlParser.unmarshal(XXE_BPMN, BpmnDefinitionsModel.class);
            if (result.getProcess() != null && result.getProcess().getStartEvents() != null) {
                for (var event : result.getProcess().getStartEvents()) {
                    assertThat(event.getName()).doesNotContain("root:");
                    assertThat(event.getName()).doesNotContain("/etc/passwd");
                }
            }
        } catch (EngineException e) {
            assertThat(e.getMessage()).doesNotContain("root:");
        }
    }
}
