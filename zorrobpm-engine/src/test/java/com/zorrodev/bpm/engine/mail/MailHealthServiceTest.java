package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-INT-5 criteria 7, 9: unit tests for MailHealthService.
 * Verifies health DTO aggregation from MailProperties + MailStatus,
 * and the live reachability probe (CTO HOLD round 3, criteria 7/9 "доступность").
 */
class MailHealthServiceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> providerOf(Supplier<JavaMailSender> s) {
        ObjectProvider<JavaMailSender> p = Mockito.mock(ObjectProvider.class);
        Mockito.when(p.getIfAvailable()).thenAnswer(inv -> s.get());
        return p;
    }

    private static MailHealthService service(MailProperties props, MailStatus status,
                                             Supplier<JavaMailSender> sender) {
        return new MailHealthService(props, status, providerOf(sender));
    }

    @Test
    void criterion7_configuredProperties_returnsConfigured() {
        MailProperties props = new MailProperties("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        MailStatus status = new MailStatus();
        MailHealthService service = service(props, status, () -> null);

        MailHealthDTO health = service.getHealth();

        assertThat(health.isConfigured()).isTrue();
        assertThat(health.getLastSuccess()).isNull();
        assertThat(health.getLastError()).isNull();
        assertThat(health.getLastErrorMessage()).isNull();
        assertThat(health.getReachable()).isNull();
    }

    @Test
    void criterion7_missingHost_returnsNotConfigured() {
        MailProperties props = new MailProperties(null, 587, "user", "pass", "from@test.com", "");
        MailStatus status = new MailStatus();
        MailHealthService service = service(props, status, () -> null);

        MailHealthDTO health = service.getHealth();

        assertThat(health.isConfigured()).isFalse();
    }

    @Test
    void criterion9_afterSuccess_showsLastSuccess() {
        MailProperties props = new MailProperties("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        MailStatus status = new MailStatus();
        status.recordSuccess();
        MailHealthService service = service(props, status, () -> null);

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
        MailHealthService service = service(props, status, () -> null);

        MailHealthDTO health = service.getHealth();

        assertThat(health.isConfigured()).isTrue();
        assertThat(health.getLastError()).isNotNull();
        assertThat(health.getLastErrorMessage()).contains("Connection refused");
    }

    // ==================== Reachability probe (criteria 7/9) ====================

    @Test
    void probe_noTransport_reachableUnknown() {
        MailProperties props = new MailProperties("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        MailHealthService svc = service(props, new MailStatus(), () -> null);

        // No JavaMailSender bean (test profile / unconfigured) — nothing to probe.
        assertThat(svc.probeReachable()).isNull();
        assertThat(svc.getHealth().getReachable()).isNull();
    }

    @Test
    void probe_unreachableHost_returnsFalse() {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost("127.0.0.1");
        impl.setPort(1);              // nothing listens here; connection refused immediately
        impl.setUsername("u");
        impl.setPassword("p");

        MailHealthService svc = service(
            new MailProperties("127.0.0.1", 1, "u", "p", "from@test.com", ""),
            new MailStatus(), () -> impl);

        assertThat(svc.probeReachable()).isFalse();
    }
}
