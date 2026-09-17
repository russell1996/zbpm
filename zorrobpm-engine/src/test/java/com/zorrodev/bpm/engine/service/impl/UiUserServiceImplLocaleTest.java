package com.zorrodev.bpm.engine.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-QW-1 S-11: {@link UiUserServiceImpl#normalizeEmail} must be locale-stable.
 * The method deliberately uses plain {@code toLowerCase()} (symmetric with the
 * readers), so under tr_TR it must still produce the same bytes as ROOT for
 * protocol tokens. This pins the S-11 decision: if a future locale ever breaks
 * it, this test — not a prod incident — says so first.
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
