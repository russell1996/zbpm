package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.UserContract;
import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.dto.query.UiUserQuery;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import com.zorrodev.bpm.engine.service.UiUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
public class UserResource implements UserContract {

    private final UiUserService userService;
    private final UserInvitationService invitationService;
    private final AuditLogService auditLogService;
    private final HttpServletRequest request;

    @Override
    public PagedDataDTO<UiUser> getUsers(@ParameterObject UiUserQuery query) {
        return userService.find(query);
    }

    @Override
    public UiUser getUser(@PathVariable UUID id) {
        try {
            return userService.getById(id);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    @Override
    public IdDTO createUser(@RequestBody CreateUiUserDTO dto) {
        try {
            UUID id = userService.create(dto);
            // WO-ACL-18: the chosen creation path is recorded (criterion 3), and an
            // invitation link is emailed when the INVITE path was selected.
            if ("INVITE".equalsIgnoreCase(dto.getCreationMode())) {
                invitationService.createInvitation(id, principalFromRequest());
                auditLogService.record(principalFromRequest(), "USER_CREATE_INVITE", null, id.toString());
            } else {
                auditLogService.record(principalFromRequest(), "USER_CREATE_PASSWORD", null, id.toString());
            }
            return id(id);
        } catch (EngineException e) {
            log.warn("Failed to create user: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User creation failed");
        }
    }

    @Override
    public IdDTO updateUser(@PathVariable UUID id, @RequestBody UpdateUiUserDTO dto) {
        try {
            return id(userService.update(id, dto));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    /** WO-ACL-18 criterion 10: admin "reset password" button — issues a reset link. */
    @org.springframework.web.bind.annotation.PostMapping("/users/{id}/reset-password")
    public IdDTO resetPassword(@PathVariable UUID id) {
        try {
            invitationService.adminReset(id, principalFromRequest());
            return id(id);
        } catch (EngineException e) {
            log.warn("Failed to reset password for {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private Principal principalFromRequest() {
        TokenService.Claims claims = (TokenService.Claims) request.getAttribute("authClaims");
        if (claims == null) return null;
        return new Principal.UserPrincipal(claims.userId(), claims.username(), claims.role());
    }

    private IdDTO id(UUID value) {
        IdDTO result = new IdDTO();
        result.setId(value);
        return result;
    }
}
