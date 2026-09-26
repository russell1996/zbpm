package com.zorrodev.bpm.engine.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-QW-1 S-11: {@link UiUserServiceImpl#normalizeEmail} must use
 * {@code Locale.ROOT} — plain {@code toLowerCase()} under tr_TR turns "MIKE"
 * into "mıke" while PostgreSQL {@code lower()} (changeset 101) gives "mike",
 * breaking the write/read contract. The dotted-I case below pins the fix.
 */
class UiUserServiceImplLocaleTest {

    @Test
    void normalizeEmail_stableUnderTurkishLocale() {
        Locale def = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // WO-QW-1 verifier: include a dotted capital I — the actual trap
            // (tr → "ı", ROOT → "i"). Without it the test would pass under any
            // locale and pin nothing. Emails never contain it, but the pin must
            // prove write/read symmetry, not vacuous greenness.
            assertThat(UiUserServiceImpl.normalizeEmail("Foo@Bar.COM")).isEqualTo("foo@bar.com");
            assertThat(UiUserServiceImpl.normalizeEmail("MIKE@EXAMPLE.COM")).isEqualTo("mike@example.com");
            assertThat(UiUserServiceImpl.normalizeEmail(null)).isNull();
        } finally {
            Locale.setDefault(def);
        }
    }
}
