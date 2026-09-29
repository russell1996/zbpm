package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.RejectRegistrationDTO;
import com.zorrodev.bpm.contract.model.UiUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.UUID;

/**
 * WO-REG-5: SUPER_ADMIN-only queue for self-registrations — list pending, approve, reject.
 * Mirrors {@code ProcessSubmissionContract} split (self-service vs SUPER_ADMIN queue).
 */
public interface RegistrationAdminContract {

    @GetExchange("/admin/registrations")
    List<UiUser> listPendingRegistrations();

    @PostExchange("/admin/registrations/{id}/approve")
    void approveRegistration(@PathVariable UUID id);

    @PostExchange("/admin/registrations/{id}/reject")
    void rejectRegistration(@PathVariable UUID id, @RequestBody RejectRegistrationDTO dto);
}
