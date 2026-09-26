package com.zorrodev.bpm.engine.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSettingsCryptoTest {

    private static final String KEY_HEX =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void roundTrip_decryptRecoversPlaintext() {
        MailSettingsCrypto crypto = new MailSettingsCrypto(KEY_HEX);
        String stored = crypto.encrypt("s3cr3t-pass");
        assertThat(stored).isNotEqualTo("s3cr3t-pass");
        assertThat(crypto.decrypt(stored)).isEqualTo("s3cr3t-pass");
    }

    @Test
    void wrongKey_failsToDecrypt() {
        MailSettingsCrypto a = new MailSettingsCrypto(KEY_HEX);
        MailSettingsCrypto b = new MailSettingsCrypto(
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        String stored = a.encrypt("s3cr3t-pass");
        assertThatThrownBy(() -> b.decrypt(stored))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingKey_encryptFails() {
        MailSettingsCrypto crypto = new MailSettingsCrypto("");
        assertThatThrownBy(() -> crypto.encrypt("x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not configured");
    }

    @Test
    void encryptOrNull_blankReturnsNull() {
        MailSettingsCrypto crypto = new MailSettingsCrypto(KEY_HEX);
        assertThat(crypto.encryptOrNull(null)).isNull();
        assertThat(crypto.encryptOrNull("")).isNull();
        assertThat(crypto.decryptOrNull(null)).isNull();
        assertThat(crypto.decryptOrNull("")).isNull();
    }
}
