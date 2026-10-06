package com.zorrodev.bpm.engine.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * THE definition of "the group names in a comma-separated list" for this engine — one
 * implementation, shared by the authorization side ({@link AuthorizationService}) and the query
 * side ({@code UserTaskRepository.byCandidateGroup}/{@code relatesTo}).
 *
 * <p>Why a shared class at all: the stored {@code user_tasks.candidate_groups} is a single
 * comma-separated {@code varchar}, so a group name CANNOT contain the delimiter. The moment the two
 * sides parse that string differently, the query answers a DIFFERENT question than the
 * authorization grants, which is a defect in whichever direction you look at it:
 * <ul>
 *   <li>wider — the filter matches a group the person is not in (a filter that grants more);</li>
 *   <li>narrower — a task the person may really claim disappears from "my tasks".</li>
 * </ul>
 * Copying the parse into a second place is how that drift happens (P-24: a shared guard must be
 * shared, not re-implemented).
 *
 * <p>Trim semantics are {@link String#trim()} on each element (cut at U+0020), exactly as the
 * authorization side has always done — so a hand-written {@code "sales , east"} is the two groups
 * {@code sales} and {@code east} on both sides.
 */
public final class CandidateGroups {

    /** The one character a group name may not contain: it is the list delimiter of the column. */
    public static final char DELIMITER = ',';

    private CandidateGroups() {
        // utility class
    }

    /**
     * Parses a comma-separated group list into a trimmed, non-blank set — the semantics both sides
     * of the boundary use. Order-preserving (LinkedHashSet) so error messages stay deterministic.
     */
    public static Set<String> parse(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        Set<String> names = Arrays.stream(commaSeparated.split(String.valueOf(DELIMITER)))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(names);
    }

    /**
     * Whether a single group name can be stored and matched at all. A name carrying the delimiter
     * cannot: it is indistinguishable from two names, and the {@code LIKE '%name,%'} pattern built
     * from it would then match two DIFFERENT groups at once (a real over-inclusion: a person in the
     * group {@code a,b} would be shown tasks restricted to {@code x,a,b,y}).
     *
     * @return false when the name contains {@link #DELIMITER}
     */
    public static boolean isStorable(String groupName) {
        return groupName != null && groupName.indexOf(DELIMITER) < 0;
    }
}
