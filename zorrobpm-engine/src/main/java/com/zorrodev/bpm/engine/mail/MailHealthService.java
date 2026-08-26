package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.contract.dto.MailHealthDTO;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Transport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailHealthService {

    private final MailConfigResolver configResolver;
    private final MailTransportFactory transportFactory;
    private final MailStatus mailStatus;

    /**
     * Returns the current mail health status.
     */
    public MailHealthDTO getHealth() {
        ResolvedMailConfig cfg = configResolver.getEffectiveConfig();
        MailHealthDTO dto = new MailHealthDTO();
        dto.setConfigured(isConfigured(cfg));
        dto.setReachable(probeReachable(cfg));
        dto.setLastSuccess(mailStatus.getLastSuccess());
        dto.setLastError(mailStatus.getLastError());
        dto.setLastErrorMessage(mailStatus.getLastErrorMessage());
        return dto;
    }

    private boolean isConfigured(ResolvedMailConfig cfg) {
        return cfg != null
            && cfg.host() != null && !cfg.host().isBlank()
            && cfg.port() != null
            && cfg.from() != null && !cfg.from().isBlank();
    }

    /**
     * Live reachability probe: opens an SMTP transport to the configured host.
     * null when there is nothing to probe (no host configured).
     * A bad-credentials rejection still means the server is reachable → true.
     */
    Boolean probeReachable(ResolvedMailConfig cfg) {
        if (cfg == null || cfg.host() == null || cfg.host().isBlank()) {
            return null;
        }
        try {
            JavaMailSenderImpl impl = transportFactory.build(cfg.host(), cfg.port(), cfg.username(), cfg.password());
            Transport transport = impl.getSession().getTransport("smtp");
            transport.connect(impl.getHost(), impl.getPort(), impl.getUsername(), impl.getPassword());
            transport.close();
            return true;
        } catch (AuthenticationFailedException e) {
            log.info("Mail reachability probe: server reachable, authentication rejected");
            return true;
        } catch (Exception e) {
            log.warn("Mail reachability probe failed for {}:{}: {}", cfg.host(), cfg.port(), e.getMessage());
            return false;
        }
    }
}
