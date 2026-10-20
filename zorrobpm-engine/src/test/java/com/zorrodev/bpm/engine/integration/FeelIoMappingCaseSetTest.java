package com.zorrodev.bpm.engine.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-30 F1 — guard for the evidence base itself.
 *
 * <p>The conformance matrix is only meaningful if every measured case is present in
 * {@code feel-iomapping-cases.json} and is structurally usable by the harness. This
 * test does NOT assert engine behavior (F1 is measurement-only); it protects the
 * 72-case corpus from silently losing or duplicating a case, which would make the
 * matrix claim broader coverage than was actually measured.</p>
 */
class FeelIoMappingCaseSetTest {

    /** 72 кейса Ф1 + 12 кейсов условий гейтвея (WO-C8-54) + 5 кейсов корня 3 (WO-C8-55)
     *  + 3 кейса MI-полей корня 3 (WO-C8-55) + 2 кейса «правильная форма правки outputElement»
     *  (WO-C8-55, ответ maintainer: что консьюмеру писать вместо статичного значения). */
    private static final int EXPECTED_CASES = 94;
    private static final Set<String> WRAPPERS = Set.of("subprocess", "mi", "gateway");
    private static final List<String> OPERATION_FIELDS = List.of(
        "input", "output", "completionVars", "containerInput", "containerOutput", "outputElement"
    );

    @Test
    void caseSetIsCompleteAndWellFormed() throws Exception {
        Map<String, Object> root;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("feel-iomapping-cases.json")) {
            assertThat(in).as("feel-iomapping-cases.json on test classpath").isNotNull();
            root = new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() { });
        }

        assertThat(root.get("campaign"))
            .isEqualTo("WO-OPS-30 F1 — FEEL and ioMapping differential measurements");
        assertThat(root.get("oracle"))
            .isEqualTo("Zeebe 8.5.0 (docs: Camunda 8.9)");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("cases");

        assertThat(cases).hasSize(EXPECTED_CASES);
        assertThat(cases)
            .extracting(c -> c.get("id"))
            .doesNotHaveDuplicates()
            .allSatisfy(id -> assertThat(id).isInstanceOf(String.class));

        assertThat(cases).allSatisfy(c -> {
            String id = (String) c.get("id");
            assertThat(id).as("case id").isNotBlank();
            assertThat(c.get("title")).as("title of %s", id).isInstanceOf(String.class);
            assertThat((String) c.get("title")).as("title of %s", id).isNotBlank();
            assertThat(c.get("docs")).as("docs of %s", id).isInstanceOf(String.class);
            assertThat((String) c.get("docs")).as("docs of %s", id).isNotBlank();
            if (c.containsKey("wrap")) {
                assertThat(WRAPPERS).as("wrap of %s", id).contains((String) c.get("wrap"));
            }
            if ("gateway".equals(c.get("wrap"))) {
                // WO-C8-54: a condition case carries the condition text and whether
                // the second flow is the default one.
                assertThat(c.get("condition")).as("condition of %s", id).isInstanceOf(String.class);
                assertThat((String) c.get("condition")).as("condition of %s", id).isNotBlank();
                assertThat(c.get("defaultFlow")).as("defaultFlow of %s", id).isInstanceOf(Boolean.class);
            } else {
                assertThat(OPERATION_FIELDS.stream().anyMatch(c::containsKey))
                    .as("operation field of %s", id)
                    .isTrue();
            }
        });

        assertThat(cases)
            .filteredOn(c -> "mi".equals(c.get("wrap")))
            .allSatisfy(c -> assertThat(c).containsKey("outputCollection"));

        assertThat(cases)
            .extracting(c -> c.get("id"))
            .contains("path-quoted-backtick", "path-quoted-getvalue",
                // WO-C8-54: the gateway-condition cases of G-Q item 3
                "cond-missing-var-default", "cond-missing-compare-default",
                "cond-not-comparable-default", "cond-unknown-function-default",
                "cond-explicit-null-default", "cond-true-default");

        // WO-C8-54: the null-semantics cases of the root carry the live-8.5
        // expectations (the values the harness asserts).
        assertThat(cases)
            .filteredOn(c -> c.containsKey("expect"))
            .extracting(c -> c.get("id"))
            .contains("num-round", "str-null", "null-literal", "missing-bare", "present-null",
                "missing-default", "path-missing", "mixed-string-num", "mixed-lt",
                "path-index-zero", "path-oob",
                // WO-C8-55: the root-3 cases (a source without '=' is a static value; an
                // output source without '=' is refused at deploy)
                "noeq-literal", "noeq-var", "noeq-number", "noeq-json", "noeq-spaces",
                "noeq-output-static", "noeq-output-static-var",
                // WO-C8-55: the MI half of root 3 (the MI fields are EXPRESSIONS on live 8.5 —
                // a static value there is refused at deploy)
                "mi-noeq-input-collection", "mi-noeq-output-element", "mi-noeq-output-element-json",
                // WO-C8-55: the two forms the CONSUMER must write instead of the refused
                // spelling — a bare reference (the value is shadowed by the pre-created null)
                // and a context (the value stays visible). Both measured on live 8.5.
                "mi-outputelement-bare-parent-var", "mi-outputelement-ctx-parent-var");
    }
}
