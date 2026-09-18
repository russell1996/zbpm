package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import com.zorrodev.bpm.engine.service.UiUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserResourceAuditTest {

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
    void createUser_inviteMode_recordsUserCreateInvite() {
        UUID id = UUID.randomUUID();
        when(userService.create(any())).thenReturn(id);

        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setCreationMode("INVITE");

        resource.createUser(dto);

        verify(invitationService).createInvitation(eq(id), any());
        // C3 (HOLD): the INVITE creation path must emit USER_CREATE_INVITE.
        verify(auditLogService).record(any(), eq("USER_CREATE_INVITE"), any(), eq(id.toString()));
    }

    @Test
    void createUser_passwordMode_recordsUserCreatePassword() {
        UUID id = UUID.randomUUID();
        when(userService.create(any())).thenReturn(id);

        CreateUiUserDTO dto = new CreateUiUserDTO();
        dto.setCreationMode("PASSWORD");

        resource.createUser(dto);

        // C3 (HOLD): the PASSWORD creation path must emit USER_CREATE_PASSWORD.
        verify(auditLogService).record(any(), eq("USER_CREATE_PASSWORD"), any(), eq(id.toString()));
    }
}
