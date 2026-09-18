package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.FormDeploymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * WO-DEBT-7 S7 — thin facade over {@link FormDeploymentService}: auth checks +
 * delegation. All JPA (reads and every {@code .save()}) lives in the service,
 * inside this class' transaction (no {@code @Transactional} on the service —
 * same as the original layout, proven by {@code DeployFormTransactionIT}:
 * post-write failure rolls the save back). Zero direct persistence imports.
 */
@Service
@RequiredArgsConstructor
public class FormOperationsImpl implements FormOperations {

    private final FormDeploymentService formDeploymentService;
    private final FormAccessSupport formAccessSupport;

    @Override
    public List<FormDTO> listForms() {
        return formDeploymentService.listForms(formAccessSupport.resolveAllowedPdIds());
    }

    @Transactional
    @Override
    public FormDTO deployForm(DeployFormDTO dto) {
        // WO-FORM-1: only SUPER_ADMIN can deploy forms
        Principal principal = formAccessSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deploy requires SUPER_ADMIN");
        }
        return formDeploymentService.deployForm(dto);
    }

    @Override
    public FormDTO getForm(String key) {
        return formDeploymentService.getForm(key, formAccessSupport.resolveAllowedPdIds());
    }
}
