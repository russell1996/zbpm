package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.MailSettingsDTO;
import com.zorrodev.bpm.engine.mail.MailHealthService;
import com.zorrodev.bpm.engine.mail.MailSettingsService;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WO-INT-6 criterion 6: the REST layer forwards the exact result/error from MailSettingsService
 * for the self test-send — it must not swallow or replace the SMTP error (which must surface
 * verbatim to the user). The SMTP behaviour itself is proven by MailSettingsServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class MailResourceTestSendResultTest {

    @Mock MailHealthService mailHealthService;
    @Mock MailSettingsService mailSettingsService;
    @Mock HttpServletRequest request;

    private MailResource resource;

    @BeforeEach
    void setUp() {
        resource = new MailResource(mailHealthService, mailSettingsService, request);
        Principal.UserPrincipal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        org.mockito.Mockito.lenient().when(request.getAttribute("principal")).thenReturn(admin);
    }

    @Test
    void criterion2_success_delegatesToServiceNoBody() {
        resource.testMailSettingsToSelf();
        org.mockito.Mockito.verify(mailSettingsService).testSendToSelf(any(Principal.class));
    }

    @Test
    void criterion2_smtpFailure_propagatedAs502_withCause() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SMTP error: 550 relay access denied"))
            .when(mailSettingsService).testSendToSelf(any(Principal.class));
        assertThatThrownBy(() -> resource.testMailSettingsToSelf())
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> {
                ResponseStatusException r = (ResponseStatusException) e;
                assertThat(r.getStatusCode().value()).isEqualTo(502);
                assertThat(r.getReason()).contains("550 relay access denied");
            });
    }

    @Test
    void criterion1_check_delegatesAndReturnsServiceResult() {
        MailSettingsDTO in = new MailSettingsDTO();
        com.zorrodev.bpm.contract.dto.MailCheckResultDTO out = new com.zorrodev.bpm.contract.dto.MailCheckResultDTO();
        out.setReachable(true);
        when(mailSettingsService.checkConnection(any(MailSettingsDTO.class), any(Principal.class))).thenReturn(out);
        assertThat(resource.checkMailSettings(in)).isSameAs(out);
    }

    @Test
    void criterion6_saveSettings_returnsServiceResult() {
        MailSettingsDTO in = new MailSettingsDTO();
        MailSettingsDTO out = new MailSettingsDTO();
        out.setPasswordSet(false);
        when(mailSettingsService.saveSettings(any(MailSettingsDTO.class), any(Principal.class))).thenReturn(out);
        assertThat(resource.saveMailSettings(in)).isSameAs(out);
    }
}
