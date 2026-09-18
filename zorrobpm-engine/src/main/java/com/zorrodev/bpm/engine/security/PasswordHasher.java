package com.zorrodev.bpm.engine.security;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Salted PBKDF2 password hashing using only the JDK (no extra dependencies).
 * Stored format: {@code pbkdf2$<iterations>$<saltB64>$<hashB64>}.
 */
@Component
public class PasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * WO-SEC-63 (F21): precomputed PBKDF2 hash used for the "unknown user" login branch.
     * For an unknown username the old code called {@code hash(password)} + {@code matches()}
     * (TWO PBKDF2 runs) versus ONE {@code matches()} for a known user — a timing oracle and
     * double CPU cost on unknown logins. With a precomputed constant the unknown-user branch
     * runs exactly one {@code matches()}, same cost as a known user. Deterministic fixed salt
     * (never stored, never compared for equality — only its KDF cost matters).
     */
    public static final String CONSTANT_TIME_DUMMY_HASH = buildDummyHash();

    private static String buildDummyHash() {
        byte[] salt = new byte[16];
        Arrays.fill(salt, (byte) 0x5A);
        byte[] hash = pbkdf2("constant-time-login-dummy".toCharArray(), salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + b64(salt) + "$" + b64(hash);
    }

    public String hash(String rawPassword) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + b64(salt) + "$" + b64(hash);
    }

    public boolean matches(String rawPassword, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations);
            return constantTimeEquals(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash password", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        // WO-SEC-30a: constant-time comparison with no early return by length.
        // Accumulate XOR of length difference + all byte differences.
        int diff = a.length ^ b.length;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
