package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.TestMain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * WO-INT-5 criterion 4, production-like wiring (CTO HOLD round 3): WITHOUT the test profile
 * and with ZORROBPM_MAIL_* unset the application context must START. Before the
 * ObjectProvider fix the MailDeliveryListener constructor required a JavaMailSender bean
 * that no-mail-config deployments do not have — context startup died (fail-fast by accident).
 */
@SpringBootTest(classes = TestMain.class,
    properties = {
        "zorrobpm.security.jwt-secret=startup-test-secret-0123456789abcdef0123456789abcdef",
        // WO-SEC-68: no-profile startup now also validates the admin password
        // (was prod-only) — production-like boot needs a strong one, same as the secret above.
        "zorrobpm.security.default-admin-password=startup-test-strong-admin-password",
        // WO-SEC-80: no-profile startup also rejects default DB/Rabbit passwords —
        // production-like boot needs strong ones, same discipline as above.
        // Isolated H2 (NOT the shared `mem:test`): the password below is only
        // accepted by H2 on a fresh database — on the shared one it fails with
        // "Wrong user name or password" (sa/empty is that DB's credential).
        "spring.datasource.url=jdbc:h2:mem:mail-no-transport",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.password=startup-test-strong-db-password",
        "spring.rabbitmq.password=startup-test-strong-rabbit-password"
    })
class MailNoTransportStartupTest {

    @Autowired ApplicationContext ctx;

    @Test
    void criterion4_appStartsWithoutMailTransport_listenerPresent_transportAbsent() {
        var listeners = ctx.getBeansOfType(MailDeliveryListener.class);
        org.assertj.core.api.Assertions.assertThat(listeners).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(ctx.getBeansOfType(JavaMailSender.class)).isEmpty();
    }
}
