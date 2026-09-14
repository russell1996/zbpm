package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.dto.SignalSubscription;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-31 CR-3: signal broadcast fan-out must be keyset-paged (≤500 rows per query,
 * deterministic id-DESC order) instead of loading the full subscription set at once.
 * Mirrors the message-correlation strategy; the broadcast loop reuses
 * {@link DBService#findSignalSubscriptions(String, UUID)} with the same batch contract.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class SignalFanOutIntegrationTests {

    private static final String SIGNAL_NAME = "rel31-go";

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private ActivityService activityService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private DBService dbService;
    @Autowired private SignalSubscriptionRepository signalSubscriptionRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;

    @Test
    @Transactional
    void paging_contract_manySubscriptions_returnsDisjointOrderedBatches() {
        List<SignalSubscriptionEntity> seeds = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            SignalSubscriptionEntity e = new SignalSubscriptionEntity();
            e.setId(UUID.randomUUID());
            e.setProcessInstanceId(UUID.randomUUID());
            e.setActivityId(UUID.randomUUID());
            e.setSignalName(SIGNAL_NAME);
            e.setConsumed(false);
            e.setCreatedAt(Instant.now());
            seeds.add(e);
        }
        signalSubscriptionRepository.saveAll(seeds);

        List<SignalSubscription> page1 = dbService.findSignalSubscriptions(SIGNAL_NAME, null);
        assertThat(page1).hasSize(DBService.FAN_OUT_BATCH_SIZE);

        UUID cursor1 = page1.get(page1.size() - 1).getId();
        List<SignalSubscription> page2 = dbService.findSignalSubscriptions(SIGNAL_NAME, cursor1);
        assertThat(page2).hasSize(DBService.FAN_OUT_BATCH_SIZE);

        UUID cursor2 = page2.get(page2.size() - 1).getId();
        List<SignalSubscription> page3 = dbService.findSignalSubscriptions(SIGNAL_NAME, cursor2);
        assertThat(page3).hasSize(200);

        List<UUID> allIds = new ArrayList<>();
        page1.forEach(s -> allIds.add(s.getId()));
        page2.forEach(s -> allIds.add(s.getId()));
        page3.forEach(s -> allIds.add(s.getId()));
        assertThat(allIds).doesNotHaveDuplicates();
        assertThat(allIds).hasSize(1200);
        // deterministic order in the DATABASE's UUID ordering (unsigned 128-bit, see helper)
        for (int i = 1; i < allIds.size(); i++) {
            assertThat(compareUuidSql(allIds.get(i - 1), allIds.get(i))).isGreaterThan(0);
        }
    }

    @Test
    @Transactional
    void broadcastSignal_over600Subscribers_wakesAllExactlyOnce() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-rel31-signal-fanout.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        List<UUID> started = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            IdDTO idDto = runtimeService.startProcessInstance(dto);
            started.add(idDto.getId());
        }
        assertThat(started).hasSize(600);
        assertThat(processInstanceRepository.findAllById(started))
            .allMatch(pi -> pi.getCompletedAt() == null); // parked on the signal catch

        activityService.broadcastSignal(SIGNAL_NAME, List.of());

        List<ProcessInstanceEntity> after = processInstanceRepository.findAllById(started);
        assertThat(after).hasSize(600);
        assertThat(after).allMatch(pi -> pi.getCompletedAt() != null);
    }

    /**
     * SQL orders UUIDs as unsigned 128-bit big-endian, whereas Java's {@link UUID#compareTo}
     * compares high/low 64-bit halves as SIGNED longs — the two disagree whenever the MSB is set.
     */
    private static int compareUuidSql(UUID a, UUID b) {
        int cmpHi = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return cmpHi != 0 ? cmpHi
            : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}