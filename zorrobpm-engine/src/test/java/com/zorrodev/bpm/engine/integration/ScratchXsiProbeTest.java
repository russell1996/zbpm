package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.BpmnSupportScanner;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.StringReader;
import java.util.List;

/** SCRATCH probe — facts only, deleted before the commit. Not part of the deliverable. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ScratchXsiProbeTest {

    @Autowired
    private BpmnParseService bpmnParseService;

    private static final List<String> HOSTS = List.of(
        "task", "scriptTask", "serviceTask", "userTask", "manualTask", "businessRuleTask",
        "sendTask", "receiveTask", "subProcess", "transaction", "callActivity");

    private static final String XSD_DIR =
        "src/test/files/nonexistent"; // unused

    private static String doc(String host, String loopXml) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                              id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="p" name="p" isExecutable="true">
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t" />
                %s
                <bpmn:sequenceFlow id="f2" sourceRef="t" targetRef="e" />
                <bpmn:endEvent id="e"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(hostXml(host, loopXml));
    }

    private static String hostXml(String host, String loopXml) {
        String ext = "serviceTask".equals(host)
            ? "<bpmn:extensionElements><zeebe:taskDefinition type=\"j\" retries=\"1\"/></bpmn:extensionElements>"
            : "";
        String incoming = "<bpmn:incoming>f1</bpmn:incoming>";
        String outgoing = "<bpmn:outgoing>f2</bpmn:outgoing>";
        String name = Character.toUpperCase(host.charAt(0)) + host.substring(1);
        String attrs = switch (host) {
            case "scriptTask" -> " scriptFormat=\"feel\"><bpmn:script>1</bpmn:script>";
            case "subProcess", "transaction" -> ">";
            default -> ">";
        };
        return "    <bpmn:" + host + " id=\"t\" name=\"" + name + "\"" + attrs
            + "\n      " + ext + incoming + outgoing + "\n      " + loopXml + "\n    </bpmn:" + host + ">";
    }

    private static String headStandard() {
        return "<bpmn:loopCharacteristics xsi:type=\"bpmn:tStandardLoopCharacteristics\" loopMaximum=\"3\">"
            + "<bpmn:loopCondition>x &lt; 3</bpmn:loopCondition></bpmn:loopCharacteristics>";
    }

    private static String headMultiInstance() {
        return "<bpmn:loopCharacteristics xsi:type=\"bpmn:tMultiInstanceLoopCharacteristics\" isSequential=\"false\">"
            + "<bpmn:loopCardinality>3</bpmn:loopCardinality></bpmn:loopCharacteristics>";
    }

    private static String directStandard() {
        return "<bpmn:standardLoopCharacteristics testBefore=\"false\" loopMaximum=\"3\">"
            + "<bpmn:loopCondition>x &lt; 3</bpmn:loopCondition></bpmn:standardLoopCharacteristics>";
    }

    private static String directMultiInstance() {
        return "<bpmn:multiInstanceLoopCharacteristics isSequential=\"false\">"
            + "<bpmn:loopCardinality>3</bpmn:loopCardinality></bpmn:multiInstanceLoopCharacteristics>";
    }

    @Test
    void probe_all22_head_and_direct_forms() {
        System.out.println("=== PROBE-1 scan/parse over 11 hosts x 4 loop spellings ===");
        for (String host : HOSTS) {
            for (String[] spell : new String[][] {
                {"HEAD-standard", headStandard()},
                {"HEAD-multiInstance", headMultiInstance()},
                {"DIRECT-standard", directStandard()},
                {"DIRECT-multiInstance", directMultiInstance()}}) {
                String d = doc(host, spell[1]);
                String findings;
                String parseState;
                try {
                    findings = BpmnSupportScanner.scan(d).stream()
                        .map(f -> f.code() + "=" + f.elementIds()).toList().toString();
                } catch (Exception e) {
                    findings = "SCAN_THREW " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                try {
                    BpmnProcessDefinitionModel m = bpmnParseService.parse(d);
                    String elements = m.getElements().stream()
                        .map(e -> e.getId() + ":" + e.getType()
                            + (e.getExtensions() != null && e.getExtensions().getMultiInstanceExtension() != null
                                ? "+MI" : ""))
                        .toList().toString();
                    parseState = "ok " + elements + " findings=" + m.getUnsupportedConstructs().stream()
                        .map(f -> f.code()).toList();
                } catch (Exception e) {
                    parseState = "PARSE_THREW " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                System.out.println("PROBE| " + host + " | " + spell[0] + " | scan=" + findings + " | " + parseState);
            }
        }
    }

    @Test
    void probe_prefix_alias_and_odd_types() {
        System.out.println("=== PROBE-2 prefix alias / odd types ===");
        String aliasDoc = """
            <?xml version="1.0" encoding="UTF-8"?>
            <b2:definitions xmlns:b2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:zz="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:xi="http://www.w3.org/2001/XMLSchema-instance"
                            id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <b2:process id="p" name="p" isExecutable="true">
                <b2:startEvent id="s"><b2:outgoing>f1</b2:outgoing></b2:startEvent>
                <b2:sequenceFlow id="f1" sourceRef="s" targetRef="t" />
                <b2:scriptTask id="t" name="t" scriptFormat="feel">
                  <b2:incoming>f1</b2:incoming>
                  <b2:outgoing>f2</b2:outgoing>
                  <b2:script>1</b2:script>
                  <b2:loopCharacteristics xi:type="zz:tStandardLoopCharacteristics" loopMaximum="3"/>
                </b2:scriptTask>
                <b2:sequenceFlow id="f2" sourceRef="t" targetRef="e" />
                <b2:endEvent id="e"><b2:incoming>f2</b2:incoming></b2:endEvent>
              </b2:process>
            </b2:definitions>
            """;
        System.out.println("PROBE| alias-prefix-xsi | scan=" + BpmnSupportScanner.scan(aliasDoc)
            .stream().map(f -> f.code() + "=" + f.elementIds()).toList());

        String foreign = doc("scriptTask", "<bpmn:loopCharacteristics xsi:type=\"bpmn:FooBar\"/>");
        String noType = doc("scriptTask", "<bpmn:loopCharacteristics loopMaximum=\"3\"/>");
        String selfRef = doc("scriptTask", "<bpmn:loopCharacteristics xsi:type=\"bpmn:tLoopCharacteristics\"/>");
        String undeclaredPrefix = doc("scriptTask",
            "<bpmn:loopCharacteristics xsi:type=\"nope:tStandardLoopCharacteristics\"/>");
        for (String[] c : new String[][] {{"foreign-type", foreign}, {"no-xsi-type", noType},
            {"abstract-self-ref", selfRef}, {"undeclared-prefix", undeclaredPrefix}}) {
            String outcome;
            try {
                outcome = "scan=" + BpmnSupportScanner.scan(c[1]).stream()
                    .map(f -> f.code() + "=" + f.elementIds()).toList()
                    + " parse=ok elems=" + bpmnParseService.parse(c[1]).getElements().stream()
                        .map(e -> e.getId()).toList();
            } catch (Exception e) {
                outcome = "THREW " + e.getClass().getName() + ": " + e.getMessage();
            }
            System.out.println("PROBE| " + c[0] + " | " + outcome);
        }
    }

    @Test
    void probe_xsd_validity() {
        System.out.println("=== PROBE-3 XSD validity (official bpmn-moddle BPMN20.xsd) ===");
        File dir = new File("../zorrobpm-frontend/node_modules/bpmn-moddle/resources/bpmn/xsd");
        if (!new File(dir, "BPMN20.xsd").isFile()) {
            System.out.println("PROBE| XSD NOT FOUND at " + dir.getAbsolutePath());
            return;
        }
        try {
            SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = f.newSchema(new File(dir, "BPMN20.xsd"));
            check(schema, "head-standard", doc("scriptTask", headStandard()));
            check(schema, "head-multiInstance", doc("scriptTask", headMultiInstance()));
            check(schema, "direct-standard", doc("scriptTask", directStandard()));
            check(schema, "direct-multiInstance-subprocess", doc("subProcess", directMultiInstance()));
            check(schema, "direct-multiInstance-serviceTask", doc("serviceTask", directMultiInstance()));
            check(schema, "head-standard-on-transaction", doc("transaction", headStandard()));
            check(schema, "foreign-type", doc("scriptTask", "<bpmn:loopCharacteristics xsi:type=\"bpmn:FooBar\"/>"));
            check(schema, "no-xsi-type", doc("scriptTask", "<bpmn:loopCharacteristics loopMaximum=\"3\"/>"));
            check(schema, "loopCondition-plain-text",
                doc("scriptTask", "<bpmn:standardLoopCharacteristics loopMaximum=\"3\">"
                    + "<bpmn:loopCondition>x &lt; 3</bpmn:loopCondition></bpmn:standardLoopCharacteristics>"));
        } catch (Exception e) {
            System.out.println("PROBE| XSD harness failed: " + e);
        }
    }

    private static void check(Schema schema, String label, String xml) {
        try {
            Validator v = schema.newValidator();
            v.validate(new StreamSource(new StringReader(xml)));
            System.out.println("PROBE| xsd " + label + " = VALID");
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage().replace('\n', ' ');
            System.out.println("PROBE| xsd " + label + " = INVALID: " + m.substring(0, Math.min(220, m.length())));
        }
    }

    @Test
    void probe_three_prefix_documents() {
        System.out.println("=== PROBE-4 the three prefix-resolution documents ===");
        String twoPrefixes = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:zz="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xi="http://www.w3.org/2001/XMLSchema-instance"
                              id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="p" name="p" isExecutable="true">
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="loopTask" />
                <bpmn:task id="loopTask" name="loopTask">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:loopCharacteristics xi:type="zz:tStandardLoopCharacteristics" loopMaximum="3" />
                </bpmn:task>
                <bpmn:sequenceFlow id="f2" sourceRef="loopTask" targetRef="e" />
                <bpmn:endEvent id="e"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;
        System.out.println("PROBE4| twoPrefixes = " + dump(twoPrefixes));
    }

    @Test
    void probe_default_ns_document() {
        String defaultNs = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <process id="p" name="p" isExecutable="true">
                <startEvent id="s"><outgoing>f1</outgoing></startEvent>
                <sequenceFlow id="f1" sourceRef="s" targetRef="loopTask" />
                <task id="loopTask" name="loopTask">
                  <incoming>f1</incoming>
                  <outgoing>f2</outgoing>
                  <loopCharacteristics xsi:type="tStandardLoopCharacteristics" loopMaximum="3" />
                </task>
                <sequenceFlow id="f2" sourceRef="loopTask" targetRef="e" />
                <endEvent id="e"><incoming>f2</incoming></endEvent>
              </process>
            </definitions>
            """;
        System.out.println("PROBE4| defaultNs = " + dump(defaultNs));
    }

    private static String dump(String xml) {
        try {
            return BpmnSupportScanner.scan(xml).stream().map(f -> f.code() + "=" + f.elementIds()).toList()
                + " | parse elems=" + "n/a";
        } catch (Exception e) {
            return "THREW " + e;
        }
    }
}
