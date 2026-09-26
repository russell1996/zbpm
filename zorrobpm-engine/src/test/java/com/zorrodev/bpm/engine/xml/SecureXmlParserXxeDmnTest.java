package com.zorrodev.bpm.engine.xml;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.dmn.xml.DmnDefinitionsModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-SEC-20: XXE protection for DMN parsing.
 * Verifies that DOCTYPE with external entity declarations are rejected.
 */
class SecureXmlParserXxeDmnTest {

    private static final String XXE_DMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE foo [
          <!ENTITY xxe SYSTEM "file:///etc/passwd">
        ]>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                     id="Definitions_1" name="Decision" namespace="http://test">
          <decision id="d1" name="Test Decision">
            <decisionTable id="dt1" hitPolicy="UNIQUE">
              <input id="in1">
                <inputExpression id="ie1" typeRef="string">&xxe;</inputExpression>
              </input>
              <output id="out1" typeRef="string"/>
              <rule id="r1">
                <inputEntry id="ie1_entry">"yes"</inputEntry>
                <outputEntry id="oe1">"approved"</outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    private static final String CLEAN_DMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                     id="Definitions_1" name="Decision" namespace="http://test">
          <decision id="d1" name="Test Decision">
            <decisionTable id="dt1" hitPolicy="UNIQUE">
              <input id="in1">
                <inputExpression id="ie1" typeRef="string">amount</inputExpression>
              </input>
              <output id="out1" typeRef="string"/>
              <rule id="r1">
                <inputEntry id="ie1_entry">"yes"</inputEntry>
                <outputEntry id="oe1">"approved"</outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    @Test
    void xxePayload_isRejected() {
        assertThatThrownBy(() -> SecureXmlParser.unmarshal(XXE_DMN, DmnDefinitionsModel.class))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("DOCTYPE");
    }

    @Test
    void cleanDmn_parsesSuccessfully() {
        DmnDefinitionsModel result = SecureXmlParser.unmarshal(CLEAN_DMN, DmnDefinitionsModel.class);
        assertThat(result).isNotNull();
        assertThat(result.getDecisions()).hasSize(1);
        assertThat(result.getDecisions().get(0).getId()).isEqualTo("d1");
    }

    @Test
    void xxePayload_doesNotLeakFileContent() {
        try {
            DmnDefinitionsModel result = SecureXmlParser.unmarshal(XXE_DMN, DmnDefinitionsModel.class);
            if (result.getDecisions() != null) {
                for (var decision : result.getDecisions()) {
                    assertThat(decision.getName()).doesNotContain("root:");
                    assertThat(decision.getName()).doesNotContain("/etc/passwd");
                }
            }
        } catch (EngineException e) {
            assertThat(e.getMessage()).doesNotContain("root:");
        }
    }
}
