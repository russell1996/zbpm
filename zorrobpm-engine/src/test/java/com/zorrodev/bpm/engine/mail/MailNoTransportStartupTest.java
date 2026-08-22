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
    properties = "zorrobpm.security.jwt-secret=startup-test-secret-0123456789abcdef0123456789abcdef")
class MailNoTransportStartupTest {

    @Autowired ApplicationContext ctx;

    @Test
    void criterion4_appStartsWithoutMailTransport_listenerPresent_transportAbsent() {
        var listeners = ctx.getBeansOfType(MailDeliveryListener.class);
        org.assertj.core.api.Assertions.assertThat(listeners).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(ctx.getBeansOfType(JavaMailSender.class)).isEmpty();
    }
}
