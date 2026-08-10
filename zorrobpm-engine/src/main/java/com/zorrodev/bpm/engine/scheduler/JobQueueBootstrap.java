package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.exchange.JobQueuesRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WO-REL-16: announces the job types of every currently deployed process definition at startup, so
 * their queues exist on the broker without waiting for a process instance to reach a service task.
 *
 * <p>Before this, {@code zorrobpm.jobs.*} queues were created lazily on the first message sent to
 * them — so on a fresh environment (or after the broker's volume was reset) a job type that had
 * never run had no queue at all: invisible in the management UI, impossible to alert on, and a
 * worker starting before the engine had to declare it itself.
 *
 * <p>Scans the latest version of each process key rather than every historical version: that covers
 * essentially all real traffic at a bounded cost, and the lazy declare on first send still covers a
 * running instance of an older version whose job type was later removed. Resolving each model also
 * warms the BPMN cache, so the first process start does not pay the parse.
 *
 * <p>Best-effort by construction: any failure (broker down, unreadable BPMN file) is logged and the
 * application starts normally — this must not turn a soft degradation into a failed startup.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class JobQueueBootstrap implements ApplicationRunner {

    private static final int PAGE_SIZE = 100;

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final BpmnService bpmnService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${zorrobpm.engine.job-queue-bootstrap-enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("WO-REL-16: job queue bootstrap disabled — queues will be declared lazily");
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            Set<String> jobTypes = collectJobTypes();
            if (jobTypes.isEmpty()) {
                log.info("WO-REL-16: no job types among deployed definitions, nothing to declare");
                return;
            }
            eventPublisher.publishEvent(new JobQueuesRequested(jobTypes));
            log.info("WO-REL-16: announced {} job type(s) from deployed definitions in {} ms: {}",
                jobTypes.size(), System.currentTimeMillis() - startedAt, jobTypes);
        } catch (Exception e) {
            // deliberately swallowed: queues stay lazily declared, the application still starts
            log.error("WO-REL-16: job queue bootstrap failed — queues will be declared lazily "
                + "on the first message instead", e);
        }
    }

    private Set<String> collectJobTypes() {
        Set<String> jobTypes = new LinkedHashSet<>();
        int pageIndex = 0;
        Page<ProcessDefinitionEntity> page;
        do {
            page = processDefinitionRepository.findAllLatest(PageRequest.of(pageIndex, PAGE_SIZE));
            for (ProcessDefinitionEntity definition : page) {
                try {
                    jobTypes.addAll(bpmnService.getProcessDefinitionModelById(definition.getId()).getJobTypes());
                } catch (Exception e) {
                    // one unreadable/unparseable definition must not cost us the other queues
                    log.warn("WO-REL-16: skipping definition {} ({} v{}) while collecting job types: {}",
                        definition.getId(), definition.getKey(), definition.getVersion(), e.toString());
                }
            }
            pageIndex++;
        } while (page.hasNext());
        return jobTypes;
    }
}
