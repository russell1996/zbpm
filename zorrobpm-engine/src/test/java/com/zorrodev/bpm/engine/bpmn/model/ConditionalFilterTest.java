package com.zorrodev.bpm.engine.bpmn.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-29: pure unit tests for {@link ConditionalFilter} parsing and matching.
 * No Spring, no wiring — the matching contract the trigger point relies on.
 */
class ConditionalFilterTest {

    @Test
    void parse_splitsAndTrimsCommaLists() {
        ConditionalFilter filter = ConditionalFilter.parse("approved, note ", "create, update");

        assertThat(filter.variableNames()).containsExactly("approved", "note");
        assertThat(filter.variableEvents()).containsExactly("create", "update");
    }

    @Test
    void parse_blankAttributesMeanUnrestricted() {
        ConditionalFilter filter = ConditionalFilter.parse(null, "  ");

        assertThat(filter.variableNames()).isEmpty();
        assertThat(filter.variableEvents()).isEmpty();
    }

    @Test
    void parse_dropsEmptyTokens() {
        ConditionalFilter filter = ConditionalFilter.parse("a,,b,", "create,,");

        assertThat(filter.variableNames()).containsExactly("a", "b");
        assertThat(filter.variableEvents()).containsExactly("create");
    }

    @Test
    void matches_emptyChangesMeansEvaluate() {
        // Trigger without a preceding write on this thread: no information, no
        // filtering (current behavior — fail-open, never skips).
        ConditionalFilter filter = ConditionalFilter.parse("approved", "create, update");

        assertThat(filter.matches(Map.of())).isTrue();
    }

    @Test
    void matches_irrelevantNameDoesNotMatch() {
        // WO-C8-29 POF at logic level: the kind passes, the name does not.
        ConditionalFilter filter = ConditionalFilter.parse("approved", "create, update");

        assertThat(filter.matches(Map.of("note", "create"))).isFalse();
        assertThat(filter.matches(Map.of("note", "update"))).isFalse();
    }

    @Test
    void matches_relevantNameAndKindMatches() {
        ConditionalFilter filter = ConditionalFilter.parse("approved", "create, update");

        assertThat(filter.matches(Map.of("approved", "create"))).isTrue();
        assertThat(filter.matches(Map.of("approved", "update"))).isTrue();
    }

    @Test
    void matches_namesOnlyFilterIgnoresKind() {
        ConditionalFilter filter = ConditionalFilter.parse("approved", null);

        assertThat(filter.matches(Map.of("approved", "create"))).isTrue();
        assertThat(filter.matches(Map.of("approved", "update"))).isTrue();
        assertThat(filter.matches(Map.of("approved", "delete"))).isTrue();
        assertThat(filter.matches(Map.of("note", "create"))).isFalse();
    }

    @Test
    void matches_eventsOnlyFilterIgnoresName() {
        ConditionalFilter filter = ConditionalFilter.parse(null, "update");

        assertThat(filter.matches(Map.of("anything", "update"))).isTrue();
        assertThat(filter.matches(Map.of("anything", "create"))).isFalse();
    }

    @Test
    void matches_createOnlyFilterSkipsUpdates() {
        ConditionalFilter filter = ConditionalFilter.parse(null, "create");

        assertThat(filter.matches(Map.of("approved", "create"))).isTrue();
        assertThat(filter.matches(Map.of("approved", "update"))).isFalse();
    }

    @Test
    void matches_deleteNeverMatchesDocumentedEvents() {
        // Docs: only create/update are supported event values — a delete change
        // passes an events-restricted filter only if "delete" is listed literally.
        ConditionalFilter eventsOnly = ConditionalFilter.parse(null, "create, update");

        assertThat(eventsOnly.matches(Map.of("approved", "delete"))).isFalse();
    }

    @Test
    void matches_unknownNameFailsOpenOnNamesDimension() {
        // Defensive guard: a null name (corrupt input — the store never records one)
        // passes names, events still gates.
        ConditionalFilter namesOnly = ConditionalFilter.parse("approved", null);
        ConditionalFilter namesAndEvents = ConditionalFilter.parse("approved", "create, update");

        assertThat(namesOnly.matches(Collections.singletonMap(null, "delete"))).isTrue();
        assertThat(namesAndEvents.matches(Collections.singletonMap(null, "delete"))).isFalse();
    }

    @Test
    void matches_anyOfSeveralChangesSuffices() {
        ConditionalFilter filter = ConditionalFilter.parse("approved", "create, update");

        assertThat(filter.matches(Map.of("note", "create", "approved", "update"))).isTrue();
        assertThat(filter.matches(Map.of("note", "create", "other", "update"))).isFalse();
    }
}
