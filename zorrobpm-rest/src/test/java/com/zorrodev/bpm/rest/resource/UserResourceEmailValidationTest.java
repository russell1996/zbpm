package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import com.zorrodev.bpm.engine.service.UiUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WO-ACL-19 (P2) criterion 6: a HUMAN account requires a valid email. The rejection is enforced in
 * {@code UiUserServiceImpl}; this test pins the REST contract — {@code UserResource} surfaces it as a
 * 4xx client error (not a 500), which is the integration edge the WO calls out.
 */
@ExtendWith(MockitoExtension.class)
class UserResourceEmailValidationTest {

    @Mock
    private UiUserService userService;
    @Mock
    private UserInvitationService invitationService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private UserResource resource;

    @Test
    void createUser_humanWithoutEmail_isRejectedWithClientError_not500() {
        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setUsername("newbie");
        dto.setUserType("HUMAN");
        dto.setCreationMode("PASSWORD");
        dto.setPassword("Password123!");
        when(userService.create(any(CreateUiUserDTO.class)))
            .thenThrow(new EngineException("Email is required for HUMAN users"));

        assertThatThrownBy(() -> resource.createUser(dto))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().is4xxClientError()).isTrue());
    }
}
