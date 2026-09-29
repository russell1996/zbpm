package com.zorrodev.bpm.httpconnector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * WO-ENG-31 Фаза 2: fail-fast валидация {@code zorrobpm.http-connector.*} на старте.
 *
 * <p>Зеркало {@code AdminPasswordValidator} (WO-SEC-68) / {@code DbRabbitPasswordValidator}
 * (WO-SEC-80): тот же {@code BeanFactoryPostProcessor}-до-бинов тайминг, тот же FATAL-стиль,
 * тот же {@code SAFE_PROFILES = dev/test} для {@code allow-private-networks} (решение CTO п.2:
 * {@code allow-private-networks=true} разрешён только вне prod-профиля).
 *
 * <p>P-41: новая security-ручка проходит ту же проверку, что соседние — здесь это значит,
 * что {@code allow-private-networks} валидируется так же строго, как парольные fail-fast'ы,
 * а не остаётся тихим ослаблением SSRF-гейта.
 */
@Slf4j
@Component
public class HttpConnectorStartupValidator implements BeanFactoryPostProcessor {

    private static final Set<String> SAFE_PROFILES = Set.of("dev", "test");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment environment = beanFactory.getBean(Environment.class);
        Binder binder = Binder.get(environment);

        boolean safeProfile = Arrays.stream(environment.getActiveProfiles()).anyMatch(SAFE_PROFILES::contains);

        boolean allowPrivate = binder.bind("zorrobpm.http-connector.allow-private-networks", Bindable.of(Boolean.class))
            .orElse(false);
        if (allowPrivate && !safeProfile) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.http-connector.allow-private-networks=true is not allowed "
                + "outside dev/test profiles (SSRF gate would accept private/reserved IP ranges). "
                + "Active profiles: " + Arrays.toString(environment.getActiveProfiles()));
        }

        int defaultConnect = binder.bind("zorrobpm.http-connector.default-connection-timeout-seconds", Bindable.of(Integer.class))
            .orElse(20);
        int defaultRead = binder.bind("zorrobpm.http-connector.default-read-timeout-seconds", Bindable.of(Integer.class))
            .orElse(20);
        int maxConnect = binder.bind("zorrobpm.http-connector.max-connection-timeout-seconds", Bindable.of(Integer.class))
            .orElse(120);
        int maxRead = binder.bind("zorrobpm.http-connector.max-read-timeout-seconds", Bindable.of(Integer.class))
            .orElse(300);
        long maxResponseBytes = binder.bind("zorrobpm.http-connector.max-response-bytes", Bindable.of(Long.class))
            .orElse(1024L * 1024L);
        int maxRedirects = binder.bind("zorrobpm.http-connector.max-redirects", Bindable.of(Integer.class))
            .orElse(0);

        if (defaultConnect <= 0 || defaultRead <= 0) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.http-connector.default-*-timeout-seconds must be positive "
                + "(got connection=" + defaultConnect + ", read=" + defaultRead + ").");
        }
        if (maxConnect < defaultConnect || maxRead < defaultRead) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.http-connector.max-*-timeout-seconds must be >= the corresponding default "
                + "(connection " + maxConnect + " < " + defaultConnect
                + " or read " + maxRead + " < " + defaultRead + ").");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.http-connector.max-response-bytes must be positive "
                + "(got " + maxResponseBytes + ").");
        }
        if (maxRedirects < 0) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.http-connector.max-redirects must be >= 0 (got " + maxRedirects + ").");
        }

        log.info("HttpConnectorStartupValidator: timeouts {}/{}, caps {}/{}, maxResponseBytes={}, maxRedirects={}, allowPrivate={}",
            defaultConnect, defaultRead, maxConnect, maxRead, maxResponseBytes, maxRedirects, allowPrivate);
    }
}
