package com.zorrodev.bpm.test;

import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class TestServiceTaskEnqueueService implements ServiceTaskEnqueueService {

    @Override
    public void enqueueAfterCommit(UUID serviceTaskId) {
        log.info("Service task enqueued => {}", serviceTaskId);
    }

    @Override
    public void enqueuePhaseListener(UUID phaseId) {
        log.info("Phase listener enqueued => {}", phaseId);
    }

}
