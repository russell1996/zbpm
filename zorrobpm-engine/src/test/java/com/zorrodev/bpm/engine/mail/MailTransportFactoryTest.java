package com.zorrodev.bpm.engine.mail;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MailTransportFactoryTest {

    @Test
    void criterion7_starttlsAlwaysRequired_andNoSslTrustOverride() {
        MailTransportFactory factory = new MailTransportFactory();
        JavaMailSenderImpl sender = factory.build("smtp.x", 587, "u", "p");
        Properties props = sender.getJavaMailProperties();
        assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true");
        // The form cannot disable TLS: there is no flag wired to these keys, and no ssl.trust override.
        assertThat(props).doesNotContainKey("mail.smtp.ssl.trust");
        assertThat(props).doesNotContainKey("mail.smtp.starttls.enableable");
    }

    @Test
    void defaultPortIs587_whenNull() {
        MailTransportFactory factory = new MailTransportFactory();
        JavaMailSenderImpl sender = factory.build("smtp.x", null, "u", "p");
        assertThat(sender.getPort()).isEqualTo(587);
    }
}
