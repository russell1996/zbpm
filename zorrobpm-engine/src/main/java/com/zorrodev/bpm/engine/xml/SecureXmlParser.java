package com.zorrodev.bpm.engine.xml;

import com.zorrodev.bpm.contract.exception.EngineException;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;

import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

import org.xml.sax.InputSource;

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
     * WO-SEC-62: {@code JAXBContext} creation is expensive — cache one per class
     * instead of {@code newInstance} on every parse. {@code JAXBContext} itself
     * is thread-safe; a fresh {@code Unmarshaller} is still created per call
     * (unmarshallers are NOT thread-safe).
     */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, JAXBContext> CONTEXT_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Test-visible accessor: proves the cache returns the same instance per class. */
    static JAXBContext contextFor(Class<?> clazz) {
        return CONTEXT_CACHE.computeIfAbsent(clazz, SecureXmlParser::newContext);
    }

    private static JAXBContext newContext(Class<?> clazz) {
        try {
            return JAXBContext.newInstance(clazz);
        } catch (Exception e) {
            throw new EngineException("XML parsing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Unmarshal XML string into a JAXB-annotated class with XXE protections.
     * Rejects XML containing DOCTYPE declarations (the primary XXE attack vector).
     */
    public static <T> T unmarshal(String xml, Class<T> clazz) {
        rejectDoctype(xml);
        try {
            // WO-AUDIT-4 (S3): defense in depth — the string reject above stays the
            // primary gate, but the parser itself is also hardened (a future caller
            // must not get a resolving parser by accident).
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            spf.setXIncludeAware(false);
            spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Unmarshaller unmarshaller = contextFor(clazz).createUnmarshaller();
            SAXSource source = new SAXSource(
                spf.newSAXParser().getXMLReader(), new InputSource(new StringReader(xml)));
            return unmarshaller.unmarshal(source, clazz).getValue();
        } catch (Exception e) {
            throw new EngineException("XML parsing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reject XML containing DOCTYPE declarations.
     * DOCTYPE is the primary XXE attack vector (entity declarations).
     * Legitimate BPMN/DMN files never contain DOCTYPE.
     * WO-SEC-62: case-insensitive scan via {@code regionMatches} — no
     * {@code toUpperCase()} full-string copy on every parse.
     */
    private static void rejectDoctype(String xml) {
        String marker = "<!DOCTYPE";
        int end = xml.length() - marker.length();
        for (int i = 0; i <= end; i++) {
            if (xml.charAt(i) == '<' && xml.regionMatches(true, i, marker, 0, marker.length())) {
                throw new EngineException(
                    "XML parsing failed: DOCTYPE declarations are not allowed (XXE protection). " +
                    "Remove the DOCTYPE from the XML.");
            }
        }
    }
}
