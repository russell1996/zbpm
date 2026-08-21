package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.engine.service.MailSender;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WO-INT-5: in-memory stub for the test profile. Captures all sent emails so tests
 * can inspect recipient, subject and body without sending anything outside.
 */
@Slf4j
public class StubMailSender implements MailSender {

    private final List<MailRecord> sent = new ArrayList<>();

    @Getter
    private boolean failOnSend = false;

    @Getter
    private String failureMessage = "Simulated SMTP failure";

    public void setFailOnSend(boolean failOnSend) {
        this.failOnSend = failOnSend;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    @Override
    public void send(String to, String subject, String body) {
        send0(to, subject, body, false);
    }

    @Override
    public void sendHtml(String to, String subject, String body) {
        send0(to, subject, body, true);
    }

    private void send0(String to, String subject, String body, boolean html) {
        if (failOnSend) {
            throw new RuntimeException(failureMessage);
        }
        sent.add(new MailRecord(to, subject, body, html));
        log.info("StubMail: to='{}' subject='{}' html={}", to, subject, html);
    }

    public List<MailRecord> getSent() {
        return Collections.unmodifiableList(sent);
    }

    public void clear() {
        sent.clear();
    }

    public record MailRecord(String to, String subject, String body, boolean html) {}
}
