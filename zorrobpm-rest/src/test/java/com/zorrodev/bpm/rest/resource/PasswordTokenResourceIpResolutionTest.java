package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.ForgotPasswordDTO;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordTokenResourceIpResolutionTest {

    @Mock
    private UserInvitationService invitationService;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private PasswordTokenResource resource;

    @Test
    void forgotPasswordUsesPreFilterClientIpAttribute_notPostRewriteLookup() {
        ForgotPasswordDTO dto = new ForgotPasswordDTO();
        dto.setEmail("user@example.com");
        // WO-SEC-84: IP приходит из атрибута, который RateLimitFilter положил ДО
        // ForwardedHeaderFilter-переписывания — ресурс больше не зовёт
        // getClientIp() из контроллера (там уже подделанное значение).
        when(request.getAttribute(RateLimitFilter.CLIENT_IP_ATTRIBUTE)).thenReturn("203.0.113.7");

        resource.forgotPassword(dto);

        verify(invitationService).requestReset("user@example.com", "203.0.113.7");
    }
}
