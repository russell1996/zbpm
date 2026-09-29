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
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import com.zorrodev.bpm.engine.service.UiUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ResponseStatus;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;
import jakarta.validation.Valid;

@RestController
@RequiredArgsConstructor
@Slf4j
public class UserResource implements UserContract {

    private final UiUserService userService;
    private final UserInvitationService invitationService;
    private final AuditLogService auditLogService;
    private final HttpServletRequest request;
    private final HttpServletResponse httpResponse;
    /** WO-SEC-67 red-team #4: close/narrow live SSE streams on role/deactivation change. */
    private final SseEventStreamService sseEventStreamService;

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

    /**
     * WO-API-1 (API-1): create → 201 + Location (контракт не тронут).
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public IdDTO createUser(@Valid @RequestBody CreateUiUserDTO dto) {
        try {
            UUID id = userService.create(dto);
            // WO-ACL-18: the chosen creation path is recorded (criterion 3), and an
            // invitation link is emailed when the INVITE path was selected.
            if ("INVITE".equalsIgnoreCase(dto.getCreationMode())) {
                invitationService.createInvitation(id, getPrincipal());
                auditLogService.record(getPrincipal(), "USER_CREATE_INVITE", null, id.toString());
            } else {
                auditLogService.record(getPrincipal(), "USER_CREATE_PASSWORD", null, id.toString());
            }
            if (httpResponse != null) {
                httpResponse.setHeader("Location", "/users/" + id);
            }
            return id(id);
        } catch (EngineException e) {
            log.warn("Failed to create user: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User creation failed");
        }
    }

    @Override
    public IdDTO updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUiUserDTO dto) {
        try {
            IdDTO result = id(userService.update(id, dto));
            // WO-SEC-67 red-team #4: role/demotion/deactivation take effect on
            // the next event via liveness anyway — this hook closes idle
            // streams NOW instead of leaving them open (delivering nothing)
            // until the next event or the 30min timeout.
            sseEventStreamService.invalidateStreams();
            return result;
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        } catch (EngineException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** WO-ACL-18 criterion 10: admin "reset password" button — issues a reset link. */
    @org.springframework.web.bind.annotation.PostMapping("/users/{id}/reset-password")
    public IdDTO resetPassword(@PathVariable UUID id) {
        try {
            invitationService.adminReset(id, getPrincipal());
            return id(id);
        } catch (EngineException e) {
            log.warn("Failed to reset password for {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * WO-SEC-69: canonical principal lookup — the same one-liner as the rest
     * of the REST layer ({@code request.getAttribute("principal")}, set by
     * JwtAuthFilter). Replaces the former {@code principalFromRequest()}, which
     * after WO-SEC-64 had become an identical private copy of this expression;
     * kept as a named method (not inlined at call sites) so every principal
     * read in this resource goes through one spelling.
     */
    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private IdDTO id(UUID value) {
        IdDTO result = new IdDTO();
        result.setId(value);
        return result;
    }
}
