package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timer-job and message-subscription query APIs. The event-based gateway arms both a timer catch and a
 * message catch on start, so a single fixture exercises both list endpoints + their filters.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class TimerMessageQueryIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;

    private UUID start() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-event-based-gateway.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    @Transactional
    @Test
    void pendingTimerJobIsListed() throws Exception {
        start();
        TimerJobQuery pending = new TimerJobQuery();
        pending.setFired(false);
        assertThat(queryService.findTimerJobs(pending, null).getData()).isNotEmpty();
        assertThat(queryService.findTimerJobs(pending, null).getData()).allMatch(j -> !j.isFired());

        TimerJobQuery fired = new TimerJobQuery();
        fired.setFired(true);
        assertThat(queryService.findTimerJobs(fired, null).getData()).isEmpty();
    }

    @Transactional
    @Test
    void waitingMessageSubscriptionIsListed() throws Exception {
        UUID pi = start();
        MessageSubscriptionQuery q = new MessageSubscriptionQuery();
        q.setProcessInstanceId(pi);
        q.setConsumed(false);
        assertThat(queryService.findMessageSubscriptions(q, null).getData())
            .extracting(MessageSubscription::getMessageName)
            .contains("approve");

        MessageSubscriptionQuery consumed = new MessageSubscriptionQuery();
        consumed.setProcessInstanceId(pi);
        consumed.setConsumed(true);
        assertThat(queryService.findMessageSubscriptions(consumed, null).getData()).isEmpty();
    }
}
