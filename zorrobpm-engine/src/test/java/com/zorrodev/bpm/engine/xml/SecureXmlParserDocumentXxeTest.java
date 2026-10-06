package com.zorrodev.bpm.engine.xml;

import com.zorrodev.bpm.contract.exception.EngineException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-ENG-34: XXE protection for the SECOND XML entry point.
 *
 * <p>{@link SecureXmlParser} gained {@code parseDocument} — a DOM view of the same XML, used by
 * {@code BpmnSupportScanner} because the constructs this WO refuses ({@code <complexGateway>},
 * {@code standardLoopCharacteristics}) have NO field in the JAXB shape and JAXB drops them silently.
 * A new parser entry point is a new attack surface: it sits on every deploy AND on the runtime model
 * cache. The existing XXE tests ({@code SecureXmlParserXxeBpmnTest}, {@code …XxeDmnTest}) cover only
 * {@code unmarshal}, so nothing pinned this one.
 *
 * <p>The expectation is not "the code looks identical" — it is that a DOCTYPE payload is refused
 * through this path exactly as through the other, and that a real model still parses.
 */
class SecureXmlParserDocumentXxeTest {

    private static final String XXE_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE foo [
          <!ENTITY xxe SYSTEM "file:///etc/passwd">
        ]>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     id="Definitions_xxe_doc" targetNamespace="http://bpmn.io/schema/bpmn">
          <process id="proc" isExecutable="true">
            <startEvent id="start">&xxe;</startEvent>
          </process>
        </definitions>
        """;

    @Test
    void xxePayload_isRejectedThroughTheDomEntryPoint() {
        // The assertion names the PRIMARY barrier's exact message, not merely "DOCTYPE": the JAXP
        // feature disallow-doctype-decl produces its own error that also contains the word DOCTYPE, so
        // a looser assertion stays green with rejectDoctype deleted — i.e. it would not pin the layer
        // this method exists to pin (caught by review round 3: the loose form passed 3/3 after the
        // string reject was removed).
        assertThatThrownBy(() -> SecureXmlParser.parseDocument(XXE_BPMN))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("DOCTYPE declarations are not allowed");
    }

    @Test
    void xxePayload_doesNotLeakFileContent() {
        // the failure mode a rejected parse must not degrade into: a document that resolves the entity
        try {
            var document = SecureXmlParser.parseDocument(XXE_BPMN);
            assertThat(document.getDocumentElement().getTextContent())
                .as("an entity must never be resolved into content")
                .doesNotContain("root:");
        } catch (EngineException e) {
            assertThat(e.getMessage()).doesNotContain("root:");
        }
    }

    @Test
    void realBpmnFile_parsesSuccessfullyThroughTheDomEntryPoint() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test1.bpmn"));

        var document = SecureXmlParser.parseDocument(bpmn);

        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("definitions");
        assertThat(document.getDocumentElement().getElementsByTagNameNS(
            "http://www.omg.org/spec/BPMN/20100524/MODEL", "process").getLength())
            .as("the document view the scanner walks must actually contain the process")
            .isGreaterThan(0);
    }
}