package com.zorrodev.bpm.httpconnector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WO-ENG-31 Фаза 2: fail-fast валидация конфига — {@code allow-private-networks=true}
 * вне dev/test роняет старт (зеркало WO-SEC-68), мусорные таймауты/лимиты — тоже.
 */
class HttpConnectorStartupValidatorTest {

    private static ConfigurableListableBeanFactory factoryWith(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        if (activeProfiles.length > 0) {
            env.setActiveProfiles(activeProfiles);
        }
        ConfigurableListableBeanFactory factory = mock(ConfigurableListableBeanFactory.class);
        when(factory.getBean(org.springframework.core.env.Environment.class)).thenReturn(env);
        return factory;
    }

    private static ConfigurableListableBeanFactory factoryWithProperty(String key, String value, String... profiles) {
        MockEnvironment env = new MockEnvironment().withProperty(key, value);
        if (profiles.length > 0) {
            env.setActiveProfiles(profiles);
        }
        ConfigurableListableBeanFactory factory = mock(ConfigurableListableBeanFactory.class);
        when(factory.getBean(org.springframework.core.env.Environment.class)).thenReturn(env);
        return factory;
    }

    @Test
    void allowPrivate_true_outsideSafeProfiles_failsFast() {
        ConfigurableListableBeanFactory factory =
            factoryWithProperty("zorrobpm.http-connector.allow-private-networks", "true", "prod");
        assertThatThrownBy(() -> new HttpConnectorStartupValidator().postProcessBeanFactory(factory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("allow-private-networks");
    }

    @Test
    void allowPrivate_true_withoutAnyProfile_failsFast() {
        // Дефолтный профиль (без имени) — НЕ safe: валидация обязана сработать и здесь (урок WO-SEC-68).
        ConfigurableListableBeanFactory factory =
            factoryWithProperty("zorrobpm.http-connector.allow-private-networks", "true");
        assertThatThrownBy(() -> new HttpConnectorStartupValidator().postProcessBeanFactory(factory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("allow-private-networks");
    }

    @Test
    void allowPrivate_true_onDevProfile_passes() {
        ConfigurableListableBeanFactory factory =
            factoryWithProperty("zorrobpm.http-connector.allow-private-networks", "true", "dev");
        new HttpConnectorStartupValidator().postProcessBeanFactory(factory);
    }

    @Test
    void allowPrivate_true_onTestProfile_passes() {
        ConfigurableListableBeanFactory factory =
            factoryWithProperty("zorrobpm.http-connector.allow-private-networks", "true", "test");
        new HttpConnectorStartupValidator().postProcessBeanFactory(factory);
    }

    @Test
    void zeroDefaultTimeout_failsFast() {
        ConfigurableListableBeanFactory factory =
            factoryWithProperty("zorrobpm.http-connector.default-read-timeout-seconds", "0", "dev");
        assertThatThrownBy(() -> new HttpConnectorStartupValidator().postProcessBeanFactory(factory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    void maxBelowDefault_failsFast() {
        ConfigurableListableBeanFactory factory =
            factoryWithProperty("zorrobpm.http-connector.max-read-timeout-seconds", "5", "dev");
        assertThatThrownBy(() -> new HttpConnectorStartupValidator().postProcessBeanFactory(factory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-");
    }

    @Test
    void zeroMaxResponseBytes_failsFast() {
        ConfigurableListableBeanFactory factory =
            factoryWithProperty("zorrobpm.http-connector.max-response-bytes", "0", "dev");
        assertThatThrownBy(() -> new HttpConnectorStartupValidator().postProcessBeanFactory(factory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-response-bytes");
    }

    @Test
    void negativeMaxRedirects_failsFast() {
        ConfigurableListableBeanFactory factory =
            factoryWithProperty("zorrobpm.http-connector.max-redirects", "-1", "dev");
        assertThatThrownBy(() -> new HttpConnectorStartupValidator().postProcessBeanFactory(factory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-redirects");
    }

    @Test
    void saneDefaults_pass() {
        new HttpConnectorStartupValidator().postProcessBeanFactory(factoryWith("dev"));
    }
}
