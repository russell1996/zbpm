package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Transport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

/**
 * WO-INT-5 criteria 7, 9: aggregates mail configuration and delivery status
 * into a DTO for the REST health endpoint. Replaces actuator HealthIndicator
 * (which requires spring-boot-starter-actuator, incompatible with java.version=17).
 * <p>
 * Criteria 7/9 "доступность" (CTO HOLD round 3): {@link #probeReachable()} performs a live
 * SMTP connect against the configured server. A bad-credentials rejection still means the
 * SERVER is reachable, so it maps to true; only connection failures map to false. When no
 * transport exists (test profile / not configured) the probe yields null ("unknown").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailHealthService {

    private final MailProperties mailProperties;
    private final MailStatus mailStatus;
    private final ObjectProvider<JavaMailSender> javaMailSenderProvider;

    /**
     * Returns the current mail health status.
     */
    public MailHealthDTO getHealth() {
        MailHealthDTO dto = new MailHealthDTO();
        dto.setConfigured(mailProperties != null && mailProperties.isConfigured());
        dto.setReachable(probeReachable());
        dto.setLastSuccess(mailStatus.getLastSuccess());
        dto.setLastError(mailStatus.getLastError());
        dto.setLastErrorMessage(mailStatus.getLastErrorMessage());
        return dto;
    }

    /**
     * Live reachability probe: opens an SMTP transport to the configured host.
     * null when there is nothing to probe (no JavaMailSender bean or unknown impl).
     */
    Boolean probeReachable() {
        JavaMailSender sender = javaMailSenderProvider.getIfAvailable();
        if (!(sender instanceof JavaMailSenderImpl impl)) {
            return null;
        }
        try {
            Transport transport = impl.getSession().getTransport("smtp");
            transport.connect(impl.getHost(), impl.getPort(), impl.getUsername(), impl.getPassword());
            transport.close();
            return true;
        } catch (AuthenticationFailedException e) {
            // The server answered and rejected the credentials — connectivity itself is fine.
            log.info("Mail reachability probe: server reachable, authentication rejected");
            return true;
        } catch (Exception e) {
            log.warn("Mail reachability probe failed for {}:{}: {}",
                impl.getHost(), impl.getPort(), e.getMessage());
            return false;
        }
    }
}
