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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordTokenResourceIpResolutionTest {

    @Mock
    private UserInvitationService invitationService;
    @Mock
    private RateLimitFilter rateLimitFilter;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private PasswordTokenResource resource;

    @Test
    void forgotPasswordUsesProxyAwareClientIp_notRawRemoteAddr() {
        ForgotPasswordDTO dto = new ForgotPasswordDTO();
        dto.setEmail("user@example.com");
        when(rateLimitFilter.getClientIp(any(HttpServletRequest.class))).thenReturn("203.0.113.7");

        resource.forgotPassword(dto);

        // B5 (HOLD): the resource must delegate IP resolution to the proxy-aware
        // RateLimitFilter.getClientIp, NOT call request.getRemoteAddr() directly.
        verify(rateLimitFilter).getClientIp(request);
        verify(invitationService).requestReset("user@example.com", "203.0.113.7");
    }
}
