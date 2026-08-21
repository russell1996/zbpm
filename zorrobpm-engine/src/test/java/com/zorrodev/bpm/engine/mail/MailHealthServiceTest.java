package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-INT-5 criteria 7, 9: unit tests for MailHealthService.
 * Verifies health DTO aggregation from MailProperties + MailStatus.
 */
class MailHealthServiceTest {

    @Test
    void criterion7_configuredProperties_returnsConfigured() {
        MailProperties props = new MailProperties("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        MailStatus status = new MailStatus();
        MailHealthService service = new MailHealthService(props, status);

        MailHealthDTO health = service.getHealth();

        assertThat(health.isConfigured()).isTrue();
        assertThat(health.getLastSuccess()).isNull();
        assertThat(health.getLastError()).isNull();
        assertThat(health.getLastErrorMessage()).isNull();
    }

    @Test
    void criterion7_missingHost_returnsNotConfigured() {
        MailProperties props = new MailProperties(null, 587, "user", "pass", "from@test.com", "");
        MailStatus status = new MailStatus();
        MailHealthService service = new MailHealthService(props, status);

        MailHealthDTO health = service.getHealth();

        assertThat(health.isConfigured()).isFalse();
    }

    @Test
    void criterion9_afterSuccess_showsLastSuccess() {
        MailProperties props = new MailProperties("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        MailStatus status = new MailStatus();
        status.recordSuccess();
        MailHealthService service = new MailHealthService(props, status);

        MailHealthDTO health = service.getHealth();

        assertThat(health.isConfigured()).isTrue();
        assertThat(health.getLastSuccess()).isNotNull();
        assertThat(health.getLastError()).isNull();
    }

    @Test
    void criterion9_afterFailure_showsLastError() {
        MailProperties props = new MailProperties("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        MailStatus status = new MailStatus();
        status.recordError("Connection refused");
        MailHealthService service = new MailHealthService(props, status);

        MailHealthDTO health = service.getHealth();

        assertThat(health.isConfigured()).isTrue();
        assertThat(health.getLastError()).isNotNull();
        assertThat(health.getLastErrorMessage()).contains("Connection refused");
    }
}
