package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RegistrationAdminContract;
import com.zorrodev.bpm.contract.dto.RejectRegistrationDTO;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.RegistrationAdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;

/**
 * WO-REG-5: SUPER_ADMIN queue — approve/reject/listPending. Mirrors
 * {@code ProcessSubmissionResource} SUPER_ADMIN-only queue (same requireSuperAdmin helper,
 * same deny-by-default via {@code JwtAuthFilter} + role check inside).
 */
@RestController
@RequiredArgsConstructor
public class RegistrationAdminResource implements RegistrationAdminContract {

    private final RegistrationAdminService adminService;
    private final HttpServletRequest request;

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private void requireSuperAdmin() {
        Principal principal = getPrincipal();
        if (principal == null || !principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPER_ADMIN required");
        }
    }

    private Principal requireSuperAdminPrincipal() {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPER_ADMIN required");
        }
        return principal;
    }

    @Override
    public List<UiUser> listPendingRegistrations() {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        requireSuperAdmin();
        return adminService.listPendingRegistrations();
    }

    @Override
    public void approveRegistration(@PathVariable UUID id) {
        Principal principal = requireSuperAdminPrincipal();
        adminService.approveRegistration(id, principal);
    }

    @Override
    public void rejectRegistration(@PathVariable UUID id, @Valid @RequestBody RejectRegistrationDTO dto) {
        Principal principal = requireSuperAdminPrincipal();
        String reason = dto == null ? null : dto.getReason();
        adminService.rejectRegistration(id, reason, principal);
    }
}
