package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.MyProfileContract;
import com.zorrodev.bpm.contract.dto.ChangeMyPasswordDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.UiUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;
import jakarta.validation.Valid;

/**
 * WO-SEC-58: "My profile" self-service. Any authenticated user; operates strictly
 * on the caller's own account. The user-catalog guard (JwtAuthFilter /users →
 * SUPER_ADMIN) is NOT weakened by this — this is a narrow parallel path.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MyProfileResource implements MyProfileContract {

    private final UiUserService userService;
    private final AuditLogService auditLogService;
    private final HttpServletRequest request;

    @Override
    public IdDTO changeMyPassword(@Valid @RequestBody ChangeMyPasswordDTO dto) {
        Principal.UserPrincipal self = selfPrincipal();
        if (dto == null || dto.getCurrentPassword() == null || dto.getNewPassword() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currentPassword and newPassword are required");
        }
        try {
            UUID id = userService.changeOwnPassword(self.userId(), dto.getCurrentPassword(), dto.getNewPassword());
            // Audit WITHOUT any password material.
            auditLogService.record(self, "PASSWORD_CHANGE_SELF", null, self.userId().toString());
            log.info("Self-service password changed for user={}", self.userId());
            IdDTO result = new IdDTO();
            result.setId(id);
            return result;
        } catch (EngineException e) {
            // wrong current password / weak new password / system account → 400 with reason
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    private Principal.UserPrincipal selfPrincipal() {
        Principal principal = getPrincipal();
        if (!(principal instanceof Principal.UserPrincipal u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return u;
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }
}
