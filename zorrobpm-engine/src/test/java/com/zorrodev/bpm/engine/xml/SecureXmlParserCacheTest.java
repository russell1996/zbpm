package com.zorrodev.bpm.engine.xml;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.xml.BpmnDefinitionsModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnDefinitionsModel;
import jakarta.xml.bind.JAXBContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-SEC-62: the {@code JAXBContext} cache.
 * {@code newInstance} is expensive — one cached instance per class, not one per parse.
 */
class SecureXmlParserCacheTest {

    @Test
    void contextFor_returnsSameInstancePerClass() {
        JAXBContext first = SecureXmlParser.contextFor(BpmnDefinitionsModel.class);
        JAXBContext second = SecureXmlParser.contextFor(BpmnDefinitionsModel.class);
        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first);
    }

    @Test
    void contextFor_distinguishesClasses() {
        assertThat(SecureXmlParser.contextFor(BpmnDefinitionsModel.class))
            .isNotSameAs(SecureXmlParser.contextFor(DmnDefinitionsModel.class));
    }

    @Test
    void lowercaseDoctype_isRejected() {
        // regionMatches(ignoreCase) must preserve the old toUpperCase gate exactly.
        String payload = "<?xml version=\"1.0\"?><!doctype foo><definitions/>";
        assertThatThrownBy(() -> SecureXmlParser.unmarshal(payload, BpmnDefinitionsModel.class))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("DOCTYPE");
    }
}
