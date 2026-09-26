package com.zorrodev.bpm.engine.mail;

import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * WO-INT-6 criterion 7: builds a {@link JavaMailSenderImpl} with STARTTLS always required.
 * There is no input that disables TLS — the form cannot turn it off.
 */
@Service
public class MailTransportFactory {

    public JavaMailSenderImpl build(ResolvedMailConfig cfg) {
        return build(cfg.host(), cfg.port(), cfg.username(), cfg.password());
    }

    public JavaMailSenderImpl build(String host, Integer port, String username, String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port != null ? port : 587);
        sender.setUsername(username);
        sender.setPassword(password);
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
