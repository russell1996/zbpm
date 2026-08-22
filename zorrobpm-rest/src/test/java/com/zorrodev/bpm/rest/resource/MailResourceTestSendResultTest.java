package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import com.zorrodev.bpm.engine.mail.MailHealthService;
import com.zorrodev.bpm.engine.mail.MailProperties;
import com.zorrodev.bpm.engine.mail.MailStatus;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-INT-5 criterion 8 (CTO HOLD round 3): the test-send endpoint must report SUCCESS only
 * after a real SMTP delivery attempt succeeded — never after merely enqueuing. Failure must
 * surface as an error carrying the SMTP cause text.
 */
@ExtendWith(MockitoExtension.class)
class MailResourceTestSendResultTest {

    @Mock JavaMailSender javaMailSender;
    @Mock MailHealthService mailHealthService;
    @Mock HttpServletRequest request;
    @Mock MimeMessage mimeMessage;

    private MailResource resource;

    @BeforeEach
    void setUp() {
        MailProperties props = new MailProperties("smtp.test.com", 587, "u", "p", "from@test.com", "");
        resource = new MailResource(mailHealthService, props, new MailStatus(), request);
        resource.javaMailSender = javaMailSender; // package-field injection, prod wiring is @Autowired

        Principal.UserPrincipal admin =
            new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        org.mockito.Mockito.lenient().when(request.getAttribute("principal")).thenReturn(admin);
    }

    private String send() {
        return resource.sendTestMail("admin@test.kz");
    }

    @Test
    void criterion8_smtpSuccess_reportsSuccessAfterRealSend() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        String body = send();

        // Success is reported only AFTER JavaMailSender.send actually ran
        verify(javaMailSender).send(any(MimeMessage.class));
        assertThat(body).contains("sent successfully").contains("admin@test.kz");
    }

    @Test
    void criterion8_smtpFailure_returnsGatewayError_withSmtpCause_notSuccess() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("550 relay access denied"))
            .when(javaMailSender).send(any(MimeMessage.class));

        assertThatThrownBy(this::send)
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                .as("SMTP failure must not be reported as success (500-family), not 2xx")
                .isEqualTo(502))
            .hasMessageContaining("550 relay access denied");
    }

    @Test
    void criterion8_messageBody_comesFromTemplate_withRecipient() throws Exception {
        // Real MimeMessage objects (a mock would not store content); capture at send time
        jakarta.mail.Session session = jakarta.mail.Session.getInstance(new java.util.Properties());
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));
        var sent = new java.util.concurrent.atomic.AtomicReference<MimeMessage>();
        org.mockito.Mockito.doAnswer(inv -> {
            sent.set(inv.getArgument(0));
            return null;
        }).when(javaMailSender).send(any(MimeMessage.class));

        send();

        String content = sent.get().getContent().toString();
        assertThat(content)
            .as("body must be the rendered template with the recipient substituted")
            .contains("admin@test.kz")
            .doesNotContain("${recipient}")
            .doesNotContain("${timestamp}");
    }

    @Test
    void criterion8_noTransportInProfile_returns503() {
        resource.javaMailSender = null; // test profile: no spring.mail.host → no bean

        assertThatThrownBy(() -> resource.sendTestMail("x@y.z"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(503));

        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }
}
