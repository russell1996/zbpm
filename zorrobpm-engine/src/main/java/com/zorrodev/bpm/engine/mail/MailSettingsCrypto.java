package com.zorrodev.bpm.engine.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * WO-INT-6 criterion 5: AES-256-GCM encryption for the stored SMTP password.
 * The key comes from the environment (ZORROBPM_MAIL_PASSWORD_ENCRYPTION_KEY) and is NEVER
 * persisted. When the key is absent, {@link #encrypt} fails fast — we never store a plaintext
 * password.
 */
@Service
public class MailSettingsCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final String rawKey;
    private final boolean configured;

    public MailSettingsCrypto(@Value("${zorrobpm.mail.password-encryption-key:}") String rawKey) {
        this.rawKey = rawKey == null ? "" : rawKey.trim();
        this.configured = !this.rawKey.isBlank();
    }

    public boolean isConfigured() {
        return configured;
    }

    private byte[] decodeKey() {
        byte[] bytes;
        if (rawKey.length() == KEY_BYTES * 2) {
            try {
                bytes = HexFormat.of().parseHex(rawKey);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "Mail password encryption key must be 32 raw bytes encoded as 64 hex chars or base64", e);
            }
        } else {
            try {
                bytes = Base64.getDecoder().decode(rawKey);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "Mail password encryption key must be 32 raw bytes encoded as 64 hex chars or base64", e);
            }
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("Mail password encryption key must be exactly 32 bytes");
        }
        return bytes;
    }

    public String encrypt(String secret) {
        if (!configured) {
            throw new IllegalStateException(
                "Mail password encryption key is not configured (set ZORROBPM_MAIL_PASSWORD_ENCRYPTION_KEY)");
        }
        if (secret == null) {
            return null;
        }
        try {
            byte[] keyBytes = decodeKey();
            byte[] iv = new byte[IV_BYTES];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt mail password", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            byte[] keyBytes = decodeKey();
            byte[] data = Base64.getDecoder().decode(stored);
            byte[] iv = Arrays.copyOfRange(data, 0, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(data, IV_BYTES, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt mail password", e);
        }
    }

    public String encryptOrNull(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        return encrypt(secret);
    }

    public String decryptOrNull(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        return decrypt(stored);
    }
}
