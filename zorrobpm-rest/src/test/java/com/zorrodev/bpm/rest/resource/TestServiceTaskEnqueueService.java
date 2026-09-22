package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * WO-SEC-68: test-profile-only no-op double. Was unconditional, which injected
 * a second {@code ServiceTaskEnqueueService} bean into EVERY non-test-profile
 * boot of the rest {@code TestMain} (prod/default/dev) alongside the production
 * {@code ServiceTaskEnqueueServiceImpl} ({@code @Profile("!test")}) — those boots
 * only survived because a fail-fast fired first and masked the collision.
 * Under the {@code test} profile the bean set is unchanged (double only;
 * {@code @Primary} is a harmless explicit marker while it is the sole bean).
 */
@Slf4j
@Service
@Profile("test")
@Primary
public class TestServiceTaskEnqueueService implements ServiceTaskEnqueueService {

    @Override
    public void enqueueAfterCommit(UUID serviceTaskId) {
        log.info("Service task {} enqueued", serviceTaskId);
    }

    @Override
    public void enqueuePhaseListener(UUID phaseId) {
        log.info("Phase listener {} enqueued", phaseId);
    }
}
