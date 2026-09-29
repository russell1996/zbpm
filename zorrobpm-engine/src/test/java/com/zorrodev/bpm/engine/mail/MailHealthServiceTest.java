package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-INT-6 criteria 7, 9: unit tests for MailHealthService.
 * Verifies health DTO aggregation from MailConfigResolver + MailStatus, and the live
 * reachability probe (criteria 7/9 "доступность"). The resolver/factory are collaborators:
 * the resolver supplies the effective config, the factory builds the probe transport.
 */
class MailHealthServiceTest {

    private static MailHealthService service(ResolvedMailConfig cfg, MailStatus status) {
        MailConfigResolver resolver = Mockito.mock(MailConfigResolver.class);
        Mockito.when(resolver.getEffectiveConfig()).thenReturn(cfg);
        return new MailHealthService(resolver, new MailTransportFactory(), status);
    }

    @Test
    void criterion7_configuredDbRow_returnsConfigured() {
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        MailHealthService service = service(cfg, new MailStatus());
        MailHealthDTO health = service.getHealth();
        assertThat(health.isConfigured()).isTrue();
        assertThat(health.getLastSuccess()).isNull();
        assertThat(health.getLastError()).isNull();
        assertThat(health.getLastErrorMessage()).isNull();
        assertThat(health.getReachable()).isNotNull(); // probe ran (false in offline test env)
    }

    @Test
    void criterion7_missingHost_returnsNotConfigured() {
        ResolvedMailConfig cfg = new ResolvedMailConfig(null, 587, "user", "pass", "from@test.com", "");
        MailHealthService service = service(cfg, new MailStatus());
        assertThat(service.getHealth().isConfigured()).isFalse();
    }

    @Test
    void criterion9_afterSuccess_showsLastSuccess() {
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        MailStatus status = new MailStatus();
        status.recordSuccess();
        MailHealthService service = service(cfg, status);
        MailHealthDTO health = service.getHealth();
        assertThat(health.isConfigured()).isTrue();
        assertThat(health.getLastSuccess()).isNotNull();
        assertThat(health.getLastError()).isNull();
    }

    @Test
    void criterion9_afterFailure_showsLastError() {
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        MailStatus status = new MailStatus();
        status.recordError("Connection refused");
        MailHealthService service = service(cfg, status);
        MailHealthDTO health = service.getHealth();
        assertThat(health.getLastError()).isNotNull();
        assertThat(health.getLastErrorMessage()).contains("Connection refused");
    }

    @Test
    void probe_noHost_reachableUnknown() {
        ResolvedMailConfig cfg = new ResolvedMailConfig(null, null, null, null, null, "");
        MailHealthService svc = service(cfg, new MailStatus());
        assertThat(svc.probeReachable(cfg)).isNull();
        assertThat(svc.getHealth().getReachable()).isNull();
    }

    @Test
    void probe_unreachableHost_returnsFalse() {
        ResolvedMailConfig cfg = new ResolvedMailConfig("127.0.0.1", 1, "u", "p", "from@test.com", "");
        MailHealthService svc = service(cfg, new MailStatus());
        assertThat(svc.probeReachable(cfg)).isFalse();
    }
}
