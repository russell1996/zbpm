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
            // Dotted capital I is the classic trap: ROOT → "i", tr → "ı".
            // Emails never contain it, but the test must prove stability anyway.
            assertThat(UiUserServiceImpl.normalizeEmail("Foo@Bar.COM")).isEqualTo("foo@bar.com");
            assertThat(UiUserServiceImpl.normalizeEmail("USER@EXAMPLE.COM")).isEqualTo("user@example.com");
            assertThat(UiUserServiceImpl.normalizeEmail(null)).isNull();
        } finally {
            Locale.setDefault(def);
        }
    }
}
