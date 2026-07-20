package com.zorrodev.bpm.engine.xml;

import com.zorrodev.bpm.contract.exception.EngineException;
import jakarta.xml.bind.JAXB;
import jakarta.xml.bind.JAXBContext;

import java.io.StringReader;

/**
 * Secure XML unmarshalling helper. Blocks XXE (CWE-611) by <b>rejecting any XML that contains a
 * DOCTYPE declaration</b> before it reaches the parser — DOCTYPE is the required vector for entity
 * declarations, and legitimate BPMN/DMN never uses it. This allowlist approach means no external
 * entity ever gets a chance to resolve, regardless of the underlying JAXP configuration.
 * All BPMN/DMN parsing must go through this class.
 */
public final class SecureXmlParser {

    private SecureXmlParser() {}

    /**
     * Unmarshal XML string into a JAXB-annotated class with XXE protections.
     * Rejects XML containing DOCTYPE declarations (the primary XXE attack vector).
     */
    public static <T> T unmarshal(String xml, Class<T> clazz) {
        rejectDoctype(xml);
        try {
            return JAXB.unmarshal(new StringReader(xml), clazz);
        } catch (Exception e) {
            throw new EngineException("XML parsing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reject XML containing DOCTYPE declarations.
     * DOCTYPE is the primary XXE attack vector (entity declarations).
     * Legitimate BPMN/DMN files never contain DOCTYPE.
     */
    private static void rejectDoctype(String xml) {
        String upper = xml.toUpperCase();
        if (upper.contains("<!DOCTYPE")) {
            throw new EngineException(
                "XML parsing failed: DOCTYPE declarations are not allowed (XXE protection). " +
                "Remove the DOCTYPE from the XML.");
        }
    }
}
