package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.PostgresIT;
import org.junit.jupiter.api.Tag;

/**
 * The round-12 guard-rails on a REAL PostgreSQL (V11 / P-17 / P-22), inherited from
 * {@link UserTaskQueryGuardrailsIntegrationTests} so the two suites cannot drift.
 *
 * <p>Why these specific cases must not be trusted from H2 alone — three independent reasons, all
 * of them database behaviour rather than Java:
 * <ul>
 *   <li><b>the candidate-group matcher is a SQL expression</b>, a nested
 *       {@code replace(replace(candidate_groups, ' ,', ','), ', ', ',')} inside a LIKE. H2 and
 *       PostgreSQL agree on {@code replace}, but the red-team measured the PRE-fix over-inclusion
 *       on PostgreSQL specifically, and the fix is a different expression, not the same expression
 *       with a different argument;</li>
 *   <li><b>the huge OFFSET</b> (MEDIUM-2): the clamped page is handed to the driver as
 *       {@code limit … offset 2147483640}. PostgreSQL is what production runs.</li>
 *   <li><b>the refusals</b> (400 codes) travel through the real JPA/Hibernate stack, and the
 *       pre-fix symptom of the worst one was a 500 from a driver-side check.</li>
 * </ul>
 *
 * <p>Wiring and the "pg" group are inherited from {@link PostgresIT}.
 */
@Tag("pg")
public class UserTaskQueryGuardrailsPgIT extends UserTaskQueryGuardrailsIntegrationTests {
}
