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
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * WO-ACL-19 (P0): {@code PasswordTokenResource.forgotPassword} must NEVER let an internal failure
 * escape as a 500. The endpoint is enumeration-safe and must look identical to the client, so any
 * exception from {@code requestReset} is swallowed and logged on ERROR instead.
 */
@ExtendWith(MockitoExtension.class)
class PasswordTokenResourceFailureHandlingTest {

    @Mock
    private UserInvitationService invitationService;
    @Mock
    private RateLimitFilter rateLimitFilter;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private PasswordTokenResource resource;

    @Test
    void forgotPassword_swallowsInternalFailure_andLogsError_not500() {
        ForgotPasswordDTO dto = new ForgotPasswordDTO();
        dto.setEmail("user@example.com");
        when(rateLimitFilter.getClientIp(any(HttpServletRequest.class))).thenReturn("203.0.113.7");
        doThrow(new RuntimeException("simulated DB / outbox failure"))
            .when(invitationService).requestReset("user@example.com", "203.0.113.7");

        Logger logger = (Logger) LoggerFactory.getLogger(PasswordTokenResource.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // The call must complete normally — no exception may propagate to Spring (which would
            // otherwise render a 500 to the client). This is exactly what the live incident lacked.
            assertThatCode(() -> resource.forgotPassword(dto)).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        boolean loggedError = appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("forgotPassword failed"));
        assertThat(loggedError).isTrue();
    }
}
