package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.MailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

/**
 * WO-INT-5 criterion 2, behavioral wiring (CTO HOLD round 3, POF 1 replacement).
 * The old POF mutated an annotation StubMailSender never had. This test mutates real
 * profile bindings instead: under the test profile the ONLY MailSender is the capturing
 * stub and no real SMTP transport/listener exists.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class MailProfileWiringTest {

    @Autowired ApplicationContext ctx;

    @Test
    void profileWiring_testProfile_bindsStubMailSender_andNoRealTransport() {
        var mailSenders = ctx.getBeansOfType(MailSender.class);
        org.assertj.core.api.Assertions.assertThat(mailSenders).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(mailSenders.values().iterator().next())
            .isInstanceOf(StubMailSender.class);
        org.assertj.core.api.Assertions.assertThat(ctx.getBeansOfType(SmtpMailSender.class)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(ctx.getBeansOfType(MailDeliveryListener.class)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(ctx.getBeansOfType(JavaMailSender.class)).isEmpty();
    }
}
