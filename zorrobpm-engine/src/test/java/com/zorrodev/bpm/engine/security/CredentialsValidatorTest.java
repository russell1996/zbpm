package com.zorrodev.bpm.engine.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-SEC-27b: CredentialsValidator unit tests using MockEnvironment.
 * (a) flag off + default → starts + warn logged
 * (b) flag on + default → doesn't start
 * (c) flag on + strong password → starts
 */
class CredentialsValidatorTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;
    private CredentialsValidator validator;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        logAppender.start();
        logger = (Logger) LoggerFactory.getLogger(CredentialsValidator.class);
        logger.addAppender(logAppender);
        logger.setLevel(Level.ALL);
        validator = new CredentialsValidator();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    private MockEnvironment createEnv(String dbPass, String rmqPass, String enforce) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.datasource.password", dbPass);
        env.setProperty("spring.rabbitmq.password", rmqPass);
        env.setProperty("zorrobpm.security.enforce-db-creds", enforce);
        return env;
    }

    /** (a) flag off + default → starts + warn logged */
    @Test
    void prodProfile_flagOff_defaultPassword_warnsButStarts() throws Exception {
        MockEnvironment env = createEnv("zorrodev", "zorrodev", "false");
        org.springframework.beans.factory.config.ConfigurableListableBeanFactory mockBeanFactory =
            org.mockito.Mockito.mock(org.springframework.beans.factory.config.ConfigurableListableBeanFactory.class);
        org.mockito.Mockito.when(mockBeanFactory.getBean(org.springframework.core.env.Environment.class)).thenReturn(env);

        validator.postProcessBeanFactory(mockBeanFactory);

        // Should not throw — app starts
        boolean hasDbWarn = logAppender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("DB_PASSWORD") && e.getLevel() == Level.WARN);
        boolean hasRmqWarn = logAppender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("RABBITMQ_PASSWORD") && e.getLevel() == Level.WARN);
        assertThat(hasDbWarn).as("DB password WARN logged").isTrue();
        assertThat(hasRmqWarn).as("RabbitMQ password WARN logged").isTrue();
    }

    /** (b) flag on + default → doesn't start (IllegalStateException) */
    @Test
    void prodProfile_flagOn_defaultPassword_failsFast() {
        MockEnvironment env = createEnv("zorrodev", "zorrodev", "true");
        org.springframework.beans.factory.config.ConfigurableListableBeanFactory mockBeanFactory =
            org.mockito.Mockito.mock(org.springframework.beans.factory.config.ConfigurableListableBeanFactory.class);
        org.mockito.Mockito.when(mockBeanFactory.getBean(org.springframework.core.env.Environment.class)).thenReturn(env);

        assertThatThrownBy(() -> validator.postProcessBeanFactory(mockBeanFactory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FATAL")
            .hasMessageContaining("DB_PASSWORD");
    }

    /** (c) flag on + strong password → starts */
    @Test
    void prodProfile_flagOn_strongPassword_starts() throws Exception {
        MockEnvironment env = createEnv("SuperSecretDBPass123", "SuperSecretRabbitPass123", "true");
        org.springframework.beans.factory.config.ConfigurableListableBeanFactory mockBeanFactory =
            org.mockito.Mockito.mock(org.springframework.beans.factory.config.ConfigurableListableBeanFactory.class);
        org.mockito.Mockito.when(mockBeanFactory.getBean(org.springframework.core.env.Environment.class)).thenReturn(env);

        validator.postProcessBeanFactory(mockBeanFactory);

        boolean hasInfo = logAppender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("DB_PASSWORD") && e.getLevel() == Level.INFO);
        assertThat(hasInfo).as("DB password INFO logged").isTrue();
    }
}
