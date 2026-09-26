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
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-INT-6 criterion 4 + P-66 resolution: the mail settings REST layer enforces SUPER_ADMIN on every
 * endpoint and delegates the actual work (including the self-addressed test send) to MailSettingsService.
 * The recipient of the test send is always the caller — the service resolves it from the principal — so the
 * REST layer never forwards an arbitrary address (this is what closed the P-66 allow-list bypass). The
 * previous allow-list tests are removed because the endpoint no longer accepts an arbitrary recipient.
 */
@ExtendWith(MockitoExtension.class)
class MailResourceRecipientFilterTest {

    @Mock MailHealthService mailHealthService;
    @Mock MailSettingsService mailSettingsService;
    @Mock HttpServletRequest request;

    private MailResource resource;

    @BeforeEach
    void setUp() {
        resource = new MailResource(mailHealthService, mailSettingsService, request);
    }

    private void asSuperAdmin() {
        Principal.UserPrincipal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        org.mockito.Mockito.lenient().when(request.getAttribute("principal")).thenReturn(admin);
    }

    private void asRegular() {
        Principal.UserPrincipal user = new Principal.UserPrincipal(UUID.randomUUID(), "u", "USER");
        org.mockito.Mockito.lenient().when(request.getAttribute("principal")).thenReturn(user);
    }

    @Test
    void criterion4_superAdmin_getSettings_delegates() {
        asSuperAdmin();
        MailSettingsDTO dto = new MailSettingsDTO();
        when(mailSettingsService.getSettings()).thenReturn(dto);
        assertThat(resource.getMailSettings()).isSameAs(dto);
        verify(mailSettingsService).getSettings();
    }

    @Test
    void criterion4_nonSuperAdmin_getSettings_forbidden() {
        asRegular();
        assertThatThrownBy(() -> resource.getMailSettings())
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        verify(mailSettingsService, never()).getSettings();
    }

    @Test
    void criterion4_superAdmin_testSelf_delegatesToService_noBody() {
        asSuperAdmin();
        // WO-INT-8: no DTO travels with this call any more — nothing an arbitrary caller
        // could abuse as a recipient/credential; the recipient/config are resolved server-side.
        resource.testMailSettingsToSelf();
        verify(mailSettingsService).testSendToSelf(org.mockito.Mockito.any(Principal.class));
    }

    @Test
    void criterion4_nonSuperAdmin_testSelf_forbidden() {
        asRegular();
        assertThatThrownBy(() -> resource.testMailSettingsToSelf())
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        verify(mailSettingsService, never()).testSendToSelf(any());
    }

    @Test
    void criterion4_superAdmin_check_delegatesToService() {
        asSuperAdmin();
        MailSettingsDTO in = new MailSettingsDTO();
        com.zorrodev.bpm.contract.dto.MailCheckResultDTO out = new com.zorrodev.bpm.contract.dto.MailCheckResultDTO();
        when(mailSettingsService.checkConnection(any(MailSettingsDTO.class), any(Principal.class))).thenReturn(out);
        assertThat(resource.checkMailSettings(in)).isSameAs(out);
        verify(mailSettingsService).checkConnection(eq(in), org.mockito.Mockito.any(Principal.class));
    }

    @Test
    void criterion4_nonSuperAdmin_check_forbidden() {
        asRegular();
        MailSettingsDTO in = new MailSettingsDTO();
        assertThatThrownBy(() -> resource.checkMailSettings(in))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        verify(mailSettingsService, never()).checkConnection(any(), any());
    }

    @Test
    void criterion4_superAdmin_saveSettings_delegates() {
        asSuperAdmin();
        MailSettingsDTO in = new MailSettingsDTO();
        when(mailSettingsService.saveSettings(any(MailSettingsDTO.class), any(Principal.class))).thenReturn(in);
        assertThat(resource.saveMailSettings(in)).isSameAs(in);
        verify(mailSettingsService).saveSettings(eq(in), org.mockito.Mockito.any(Principal.class));
    }
}
