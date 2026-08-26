package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.mail.MailHealthService;
import com.zorrodev.bpm.engine.mail.MailProperties;
import com.zorrodev.bpm.engine.mail.MailRecipientPolicy;
import com.zorrodev.bpm.engine.mail.MailStatus;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-INT-5 CTO HOLD round 5 / P-66: the test-send endpoint must enforce the same
 * recipient allow-list as the production sender. A disallowed address must NOT be sent
 * (and must be refused with 403), an allowed address must be sent.
 */
@ExtendWith(MockitoExtension.class)
class MailResourceRecipientFilterTest {

    @Mock JavaMailSender javaMailSender;
    @Mock MailHealthService mailHealthService;
    @Mock HttpServletRequest request;
    @Mock MimeMessage mimeMessage;

    private MailResource resource;

    @BeforeEach
    void setUp() {
        MailProperties props = new MailProperties("smtp.test.com", 587, "u", "p", "from@test.com", "");
        // allow-list restricted to one team address (test-stand configuration)
        resource = new MailResource(mailHealthService, props, new MailStatus(), request,
            new MailRecipientPolicy("allowed@test.kz"));
        resource.javaMailSender = javaMailSender;

        Principal.UserPrincipal admin =
            new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        org.mockito.Mockito.lenient().when(request.getAttribute("principal")).thenReturn(admin);
    }

    @Test
    void criterion10_allowedRecipient_isSent() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        resource.sendTestMail("allowed@test.kz");

        verify(javaMailSender).send(any(MimeMessage.class));
    }

    @Test
    void criterion10_disallowedRecipient_isRefused_andNotSent() {
        assertThatThrownBy(() -> resource.sendTestMail("employee@corp.kz"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                .as("disallowed recipient must be refused, never silently sent")
                .isEqualTo(403));

        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void criterion11_emptyAllowList_allowsAnyRecipient() throws Exception {
        MailProperties props = new MailProperties("smtp.test.com", 587, "u", "p", "from@test.com", "");
        MailResource openResource = new MailResource(mailHealthService, props, new MailStatus(), request,
            new MailRecipientPolicy("")); // empty list = no restriction
        openResource.javaMailSender = javaMailSender;
        Principal.UserPrincipal admin =
            new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        org.mockito.Mockito.lenient().when(request.getAttribute("principal")).thenReturn(admin);
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        openResource.sendTestMail("anyone@outside.example");

        verify(javaMailSender).send(any(MimeMessage.class));
    }
}
