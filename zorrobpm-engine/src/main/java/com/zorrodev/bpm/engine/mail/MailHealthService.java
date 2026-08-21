package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * WO-INT-5 criteria 7, 9: aggregates mail configuration and delivery status
 * into a DTO for the REST health endpoint. Replaces actuator HealthIndicator
 * (which requires spring-boot-starter-actuator, incompatible with java.version=17).
 */
@Service
@RequiredArgsConstructor
public class MailHealthService {

    private final MailProperties mailProperties;
    private final MailStatus mailStatus;

    /**
     * Returns the current mail health status.
     */
    public MailHealthDTO getHealth() {
        MailHealthDTO dto = new MailHealthDTO();
        dto.setConfigured(mailProperties != null && mailProperties.isConfigured());
        dto.setLastSuccess(mailStatus.getLastSuccess());
        dto.setLastError(mailStatus.getLastError());
        dto.setLastErrorMessage(mailStatus.getLastErrorMessage());
        return dto;
    }
}
