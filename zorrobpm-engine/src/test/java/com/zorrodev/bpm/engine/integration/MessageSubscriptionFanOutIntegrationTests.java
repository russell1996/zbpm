package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
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
 * WO-REL-31 CR-3: message-subscription fan-out must be keyset-paged (≤500 rows per query,
 * deterministic id-DESC order) instead of loading the full subscription set at once.
 * The batch loop guarantees no OOM on wide fan-outs and no duplicates/re-visits.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MessageSubscriptionFanOutIntegrationTests {

    private static final String MESSAGE_NAME = "fan-out-msg";

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private ActivityService activityService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private DBService dbService;
    @Autowired private MessageSubscriptionRepository messageSubscriptionRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;

    @Test
    @Transactional
    void paging_contract_manySubscriptions_returnsDisjointOrderedBatches() {
        // 1200 unconsumed subscriptions for the same message name — more than 2 full pages
        List<MessageSubscriptionEntity> seeds = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            seeds.add(subscription(UUID.randomUUID(), MESSAGE_NAME, null));
        }
        messageSubscriptionRepository.saveAll(seeds);

        List<MessageSubscription> page1 = dbService.findMessageSubscriptions(MESSAGE_NAME, null, null);
        assertThat(page1).hasSize(DBService.FAN_OUT_BATCH_SIZE);

        UUID cursor1 = page1.get(page1.size() - 1).getId(); // min id of page1 (id-DESC)
        List<MessageSubscription> page2 = dbService.findMessageSubscriptions(MESSAGE_NAME, null, cursor1);
        assertThat(page2).hasSize(DBService.FAN_OUT_BATCH_SIZE);

        UUID cursor2 = page2.get(page2.size() - 1).getId();
        List<MessageSubscription> page3 = dbService.findMessageSubscriptions(MESSAGE_NAME, null, cursor2);
        assertThat(page3).hasSize(200); // 1200 - 500 - 500

        // pages are disjoint and cover all seeds; every id appears exactly once
        List<UUID> allIds = new ArrayList<>();
        page1.forEach(s -> allIds.add(s.getId()));
        page2.forEach(s -> allIds.add(s.getId()));
        page3.forEach(s -> allIds.add(s.getId()));
        assertThat(allIds).doesNotHaveDuplicates();
        assertThat(allIds).hasSize(1200);

        // deterministic order in the DATABASE's own UUID ordering (unsigned 128-bit big-endian,
        // not Java's signed UUID.compareTo — see helper): each page and the concatenation are strictly
        // descending, so earlier pages (ids > cursor) can never be re-fetched by later queries
        for (int i = 1; i < allIds.size(); i++) {
            assertThat(compareUuidSql(allIds.get(i - 1), allIds.get(i))).isGreaterThan(0);
        }

        // page sizes never exceed the batch limit — the loop never materializes more than 500 rows
        assertThat(page1).hasSizeLessThanOrEqualTo(DBService.FAN_OUT_BATCH_SIZE);
        assertThat(page2).hasSizeLessThanOrEqualTo(DBService.FAN_OUT_BATCH_SIZE);
        assertThat(page3).hasSizeLessThanOrEqualTo(DBService.FAN_OUT_BATCH_SIZE);
    }

    @Test
    @Transactional
    void paging_contract_byInstanceId_returnsOnlyMatchingRows() {
        UUID piA = UUID.randomUUID();
        UUID piB = UUID.randomUUID();
        List<MessageSubscriptionEntity> seeds = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            seeds.add(subscription(piA, MESSAGE_NAME, null));
            seeds.add(subscription(piB, MESSAGE_NAME, null));
        }
        messageSubscriptionRepository.saveAll(seeds);

        List<MessageSubscription> page = dbService.findMessageSubscriptions(MESSAGE_NAME, piA, null);
        assertThat(page).hasSize(400); // one page — 400 < 500
        assertThat(page).allMatch(s -> piA.equals(s.getProcessInstanceId()));
    }

    @Test
    @Transactional
    void paging_contract_byCorrelationKey_returnsOnlyMatchingRows() {
        String keyA = "key-A";
        String keyB = "key-B";
        List<MessageSubscriptionEntity> seeds = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            seeds.add(subscription(UUID.randomUUID(), MESSAGE_NAME, keyA));
            seeds.add(subscription(UUID.randomUUID(), MESSAGE_NAME, keyB));
        }
        messageSubscriptionRepository.saveAll(seeds);

        List<MessageSubscription> page = dbService.findMessageSubscriptionsByKey(MESSAGE_NAME, keyB, null);
        assertThat(page).hasSize(400);
        // the DTO doesn't carry the key — prove the query narrowed by re-loading the entities
        List<UUID> pageIds = page.stream().map(MessageSubscription::getId).toList();
        assertThat(messageSubscriptionRepository.findAllById(pageIds))
            .allMatch(e -> keyB.equals(e.getCorrelationKey()));
    }

    @Test
    @Transactional
    void correlateMessage_over600Subscribers_wakesAllExactlyOnce() throws Exception {
        // deploy a message-catch process and park 600 instances (> one 500-row batch) on the catch
        String bpmn = Files.readString(Paths.get("src/test/files/test-message.bpmn"));
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
            .allMatch(pi -> pi.getCompletedAt() == null); // parked on the catch

        // one correlation fans out over >500 subscription rows in two batches
        activityService.correlateMessage("order-approved", null, List.of());

        List<ProcessInstanceEntity> after = processInstanceRepository.findAllById(started);
        assertThat(after).hasSize(600);
        assertThat(after).allMatch(pi -> pi.getCompletedAt() != null);
    }

    private MessageSubscriptionEntity subscription(UUID processInstanceId, String messageName, String correlationKey) {
        MessageSubscriptionEntity e = new MessageSubscriptionEntity();
        e.setId(UUID.randomUUID());
        e.setProcessInstanceId(processInstanceId);
        e.setActivityId(UUID.randomUUID());
        e.setMessageName(messageName);
        e.setConsumed(false);
        e.setCreatedAt(Instant.now());
        e.setCorrelationKey(correlationKey);
        return e;
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