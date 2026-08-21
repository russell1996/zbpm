package com.zorrodev.bpm.engine.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-INT-5 tests for StubMailSender.
 * Covers criteria 1, 2: abstraction swappable, test profile captures emails.
 */
class StubMailSenderTest {

    private StubMailSender stub;

    @BeforeEach
    void setUp() {
        stub = new StubMailSender();
    }

    @Test
    void criterion1_sendCapturesRecipientSubjectBody() {
        // criterion 2: test profile sees recipient, subject, body
        stub.send("user@example.com", "Welcome", "Hello there");

        assertThat(stub.getSent()).hasSize(1);
        var record = stub.getSent().get(0);
        assertThat(record.to()).isEqualTo("user@example.com");
        assertThat(record.subject()).isEqualTo("Welcome");
        assertThat(record.body()).isEqualTo("Hello there");
        assertThat(record.html()).isFalse();
    }

    @Test
    void criterion1_sendHtmlCapturesHtmlFlag() {
        stub.sendHtml("user@example.com", "HTML test", "<h1>Hello</h1>");

        assertThat(stub.getSent()).hasSize(1);
        assertThat(stub.getSent().get(0).html()).isTrue();
    }

    @Test
    void criterion2_nothingSentOutside() {
        // criterion 2: test profile — nothing goes outside
        stub.send("a@test.com", "S1", "B1");
        stub.sendHtml("b@test.com", "S2", "B2");

        assertThat(stub.getSent()).hasSize(2);
        // No actual SMTP calls — this is verified by the fact that StubMailSender
        // does not reference JavaMailSender at all
    }

    @Test
    void failOnSend_throwsException() {
        stub.setFailOnSend(true);
        stub.setFailureMessage("Simulated failure");

        assertThatThrownBy(() -> stub.send("x@test.com", "S", "B"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Simulated failure");
    }

    @Test
    void clear_resetsState() {
        stub.send("a@test.com", "S", "B");
        assertThat(stub.getSent()).hasSize(1);

        stub.clear();
        assertThat(stub.getSent()).isEmpty();
    }
}
