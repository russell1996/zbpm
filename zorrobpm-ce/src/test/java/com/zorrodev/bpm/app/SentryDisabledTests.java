package com.zorrodev.bpm.app;

import io.sentry.Sentry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Without {@code SENTRY_DSN} the application starts as before and the SDK stays off. */
@ActiveProfiles("it")
@SpringBootTest(classes = APP.class)
@TestPropertySource(properties = "SENTRY_DSN=")
class SentryDisabledTests {

    @Test
    void startsWithTheSdkOff() {
        assertThat(Sentry.isEnabled()).isFalse();
    }
}
