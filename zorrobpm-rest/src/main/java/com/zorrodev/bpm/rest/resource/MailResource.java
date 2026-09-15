package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.MailContract;
import com.zorrodev.bpm.contract.dto.MailCheckResultDTO;
import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import com.zorrodev.bpm.contract.dto.MailSettingsDTO;
import com.zorrodev.bpm.engine.mail.MailHealthService;
import com.zorrodev.bpm.engine.mail.MailSettingsService;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import jakarta.validation.Valid;

/**
 * WO-INT-6: in-app mail settings management endpoints.
 * All endpoints require SUPER_ADMIN. The actual logic lives in {@link MailSettingsService}
 * and {@link MailHealthService}; this class only enforces auth and delegates.
 */
@RestController
@RequiredArgsConstructor
public class MailResource implements MailContract {

    private final MailHealthService mailHealthService;
    private final MailSettingsService mailSettingsService;
    private final HttpServletRequest request;

    @Override
    public MailHealthDTO getMailHealth() {
        requireSuperAdmin();
        return mailHealthService.getHealth();
    }

    @Override
    public MailSettingsDTO getMailSettings() {
        requireSuperAdmin();
        return mailSettingsService.getSettings();
    }

    @Override
    public MailSettingsDTO saveMailSettings(@Valid @RequestBody MailSettingsDTO dto) {
        requireSuperAdmin();
        return mailSettingsService.saveSettings(dto, getPrincipal());
    }

    @Override
    public MailCheckResultDTO checkMailSettings(@Valid @RequestBody MailSettingsDTO dto) {
        requireSuperAdmin();
        return mailSettingsService.checkConnection(dto, getPrincipal());
    }

    @Override
    public void testMailSettingsToSelf() {
        requireSuperAdmin();
        mailSettingsService.testSendToSelf(getPrincipal());
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    private void requireSuperAdmin() {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(FORBIDDEN, "Mail management requires SUPER_ADMIN");
        }
    }
}
