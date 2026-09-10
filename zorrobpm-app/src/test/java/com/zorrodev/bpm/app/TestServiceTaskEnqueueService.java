package com.zorrodev.bpm.app;

import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * WO-AUDIT-1: test-only stub — mirrors TestServiceTaskEnqueueService from
 * zorrobpm-rest tests. The production impl is {@code @Profile("!test")}, so the
 * "test" profile needs a no-op to satisfy AdHocSubProcessHandler's constructor.
 */
@Service
public class TestServiceTaskEnqueueService implements ServiceTaskEnqueueService {

    @Override
    public void enqueueAfterCommit(UUID serviceTaskId) {
        // no-op in tests
    }

    @Override
    public void enqueuePhaseListener(UUID phaseId) {
        // no-op in tests
    }
}
