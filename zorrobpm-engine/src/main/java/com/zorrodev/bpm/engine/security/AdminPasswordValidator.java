package com.zorrodev.bpm.engine.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * WO-SEC-14 + WO-SEC-31c: reject startup if admin password is weak.
 *
 * WO-SEC-68: the check runs on EVERY profile EXCEPT the explicit safe list
 * ({@code dev}, {@code test} — same allowlist-exception as
 * {@code TokenService.SECRET_OPTIONAL_PROFILES}). The previous prod-only gate
 * silently skipped validation on any non-prod launch (default profile, staging
 * without the exact {@code prod} name, forgotten deploy flag).
 *
 * Checks:
 *  - Password must differ from default "admin"
 *  - Password must be >= 12 characters
 *  - Password must not be in a blocklist of commonly weak passwords
 *
 * Uses BeanFactoryPostProcessor to run BEFORE bean instantiation,
 * ensuring the check happens before any other bean can fail.
 */
@Slf4j
@Component
public class AdminPasswordValidator implements BeanFactoryPostProcessor {

    private static final String DEFAULT_ADMIN_PASSWORD = "admin";
    /**
     * WO-SEC-68: allowlist-EXCEPTION — validation is SKIPPED only on these
     * profiles (local dev / CI). Everywhere else (prod, staging, default
     * profile with no explicit name) it runs. Same set as
     * {@code TokenService.SECRET_OPTIONAL_PROFILES} — keep in sync.
     */
    private static final Set<String> SAFE_PROFILES = Set.of("dev", "test");
    private static final int MIN_PASSWORD_LENGTH = 12;

    /** WO-SEC-31c: blocklist of commonly weak passwords that must be rejected. */
    private static final Set<String> WEAK_PASSWORD_BLOCKLIST = Set.of(
        "admin", "password", "zorrodev", "123456",
        "qwerty", "letmein", "welcome", "monkey", "dragon",
        "master", "abc123", "passw0rd", "changeme", "default",
        "root", "toor", "test", "demo", "sample",
        // WO-QW-4 (NEW-16a): короткие слова выше недостижимы (MIN_LENGTH=12
        // отсекает раньше) — но blocklist обязан ловить и ДЛИННЫЕ шаблонные
        // пароли, проходящие length-check: удвоения/серии/раскладки ≥12.
        "passwordpassword", "qwertyqwerty", "letmeinletmein",
        "welcome123456", "adminadmin123", "password123456",
        "qwerty12345678", "123456789012", "abcdefghijkl",
        "qwertyuiopasdf", "1q2w3e4r5t6y", "password1234!"
    );

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment environment = beanFactory.getBean(Environment.class);

        boolean safeProfile = java.util.Arrays.stream(environment.getActiveProfiles())
            .anyMatch(SAFE_PROFILES::contains);
        if (safeProfile) return;

        String password = Binder.get(environment)
            .bind("zorrobpm.security.default-admin-password", Bindable.of(String.class))
            .orElse(DEFAULT_ADMIN_PASSWORD);

        if (DEFAULT_ADMIN_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.default-admin-password must be set to a secure value "
                + "in production (default 'admin' is not allowed). "
                + "Set ZORROBPM_DEFAULT_ADMIN_PASSWORD or application-prod.yml.");
        }

        // WO-SEC-31c: minimum length
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.default-admin-password must be at least "
                + MIN_PASSWORD_LENGTH + " characters.");
        }

        // WO-SEC-31c: blocklist check
        if (isBlocklisted(password)) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.default-admin-password is a known weak password. "
                + "Choose a strong, unique password for production.");
        }

        log.info("Admin password configured for production");
    }

    /**
     * WO-QW-5 (NEW-16a): leetspeak-нормализация перед сверкой с блоклистом.
     * Блоклист — список шаблонов, а прямой `contains(lower)` ловит только
     * буквальные вхождения: `Password123!` → `password123!` ни с чем не
     * совпадает и проходит.
     *
     * <p>Сверка — три EXACT-формы, без substring-contains: contains с порогом
     * ≥6 ложно реджектит сильные пароли, содержащие шаблон как подстроку
     * (`MyStr0ng!P@ssw0rd` → `mystrongipassword` содержит `password` —
     * проверено RED: агрессивный вариант роняет
     * `isWeak_strongPassword_returnsFalse`). Формы: (1) сырой lower-case —
     * как раньше, ловит `1q2w3e4r5t6y`/`passw0rd` буквально; (2) leet-нормаль
     * (`4/@→a`, `8→b`, `3→e`, `6/9→g`, `1→i`, `0→o`, `5/$→s`, `7/+→t`,
     * `2→z`, прочий шум — пробелы/дефисы/`!`/`#` — выбрасывается);
     * (3) та же нормаль без хвостовых цифр (`password123` → `password` —
     * хвосты `123!` не сила, а шум). Residual-допуск: вставка букв ВНУТРЬ
     * шаблона (`passwordipassword`) не ловится — осознанно, иначе ловим и
     * сильных (см. выше); это список, не оценка энтропии.
     */
    static boolean isBlocklisted(String password) {
        String lower = password.toLowerCase(java.util.Locale.ROOT);
        if (WEAK_PASSWORD_BLOCKLIST.contains(lower)) {
            return true;
        }
        // WO-QW-5: порядок важен — СНАЧАЛА strip хвостового шума на сырой
        // строке (`password123!` → `password`; цифры хвоста — не leet-тело),
        // ПОТОМ leet-маппинг тела (`p@ssw0rd` → `password`). Наоборот ломается:
        // leet превращает хвост `123` в `ize`, и strip его уже не видит.
        String norm = normalizeForBlocklist(stripTrailingNoise(lower));
        return WEAK_PASSWORD_BLOCKLIST.contains(norm);
    }

    /** Хвостовой шум: trailing цифры и не-буквы (`123!`, `12345678`, `!`). */
    private static String stripTrailingNoise(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if ((c >= '0' && c <= '9') || c < 'a' || c > 'z') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    static String normalizeForBlocklist(String lower) {
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            switch (c) {
                case '4', '@' -> sb.append('a');
                case '8' -> sb.append('b');
                case '3' -> sb.append('e');
                case '6', '9' -> sb.append('g');
                case '1' -> sb.append('i');
                case '0' -> sb.append('o');
                case '5', '$' -> sb.append('s');
                case '7', '+' -> sb.append('t');
                case '2' -> sb.append('z');
                default -> {
                    if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                        sb.append(c);
                    }
                    // Прочее (пробелы, дефисы, `!`, `#`, пунктуация) — шум,
                    // выбрасываем: `p@ss-w0rd!` → `password`.
                }
            }
        }
        return sb.toString();
    }

    /**
     * WO-SEC-46: check if a password is weak (too short or blocklisted).
     * Public — reusable from UiUserServiceImpl for create/update validation.
     */
    public static boolean isWeak(String password) {
        if (password == null) return true;
        if (password.length() < MIN_PASSWORD_LENGTH) return true;
        return isBlocklisted(password);
    }
}
