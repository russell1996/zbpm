package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WO-ACL-16: incident context — processName / processInstanceId / bpmnElementId / elementName.
 * Criteria 1-3 cover the enrichment itself (card + list, unchanged legacy fields, deleted
 * instance), criterion 4 pins the batch behaviour: a page of twenty incidents must issue a
 * fixed small number of SQL statements, NOT one lookup per row (the acceptance number is
 * quoted in the report).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
// WO-QW-6 (NEW3-06): orphan test MUST run before the global-scan test (explicit
// @Order, not declaration luck) — otherwise the leak this class guards against
// is unobservable in-class by construction. Other methods stay unordered.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IncidentContextIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private ProcessInstanceRepository processInstanceRepository;
    @Autowired
    private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private BpmnService bpmnService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @PersistenceContext
    private EntityManager entityManager;

    private static final int N = 20;
    // page query + count + three batch loaders (activities / instances / definitions).
    // Anything above ~6 means enrichment went per-row (N+1 -> ~60 statements for 20 rows).
    private static final long MAX_QUERIES = 8;

    @BeforeEach
    void enableStatistics() {
        statistics().setStatisticsEnabled(true);
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * WO-QW-6 (NEW3-06): the orphan-incident test below disables FK checks, which
     * commits its row outside the test transaction (H2 DDL) — the rollback at test
     * end does NOT remove it, and the next global incident scan trips over it
     * (order-dependent red/green — WO-URGENT-1 comment in
     * {@code GraphRobustnessIntegrationTests}). Delete the orphan explicitly so no
     * sibling test can observe it, whatever the class order.
     *
     * <p>The delete runs in its OWN transaction ({@code REQUIRES_NEW}): a plain
     * repository call here would join the method-level test transaction and be
     * rolled back with it (the orphan is committed — committed must be deleted
     * committed). Runs even when the test itself fails (the orphan is committed,
     * the assertions roll back).
     */
    @AfterEach
    void deleteOrphanIncidents() {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager,
            new org.springframework.transaction.support.DefaultTransactionDefinition(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW))
            .executeWithoutResult(status -> incidentRepository.deleteAll(
                incidentRepository.findAll().stream()
                    .filter(i -> "orphan incident".equals(i.getMessage()))
                    .toList()));
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private ProcessDefinition deploy(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        return processDefinitionService.addProcessDefinition(bpmn);
    }

    private UUID startWithIncident(ProcessDefinition model) {
        ProcessVariable action = new ProcessVariable();
        action.setName("action");
        action.setType(ProcessVariableType.STRING);
        action.setValue("C"); // gateway has no matching condition and no default -> incident

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(action));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        return startResult.getId();
    }

    private Incident openIncidentOf(UUID processInstanceId) {
        IncidentQuery query = new IncidentQuery();
        query.setProcessInstanceId(processInstanceId);
        PagedDataDTO<Incident> page = queryService.findIncidents(query, null);
        assertThat(page.getData()).hasSize(1);
        return page.getData().get(0);
    }

    /** WO-ACL-16 criterion 1 (card): the incident detail carries the enriched context. */
    @Transactional
    @Test
    void card_incidentHasProcessAndElementContext() throws Exception {
        ProcessDefinition model = deploy("test-gateway-no-default.bpmn");
        UUID processInstanceId = startWithIncident(model);
        Incident listEntry = openIncidentOf(processInstanceId);

        Incident card = queryService.getIncident(listEntry.getId());

        assertThat(card.getId()).isEqualTo(listEntry.getId());
        assertThat(card.getProcessName()).isEqualTo("test-gateway-no-default");
        assertThat(card.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(card.getBpmnElementId()).isEqualTo("xor");
        assertThat(card.getElementName()).isEqualTo("xor");
    }

    /** WO-ACL-16 criterion 1 (list): the paged list carries the enriched context too. */
    @Transactional
    @Test
    void list_incidentsCarryProcessAndElementContext() throws Exception {
        ProcessDefinition model = deploy("test-gateway-no-default.bpmn");
        UUID processInstanceId = startWithIncident(model);
        Incident incident = openIncidentOf(processInstanceId);

        assertThat(incident.getProcessName()).isEqualTo("test-gateway-no-default");
        assertThat(incident.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(incident.getBpmnElementId()).isEqualTo("xor");
        assertThat(incident.getElementName()).isEqualTo("xor");
    }

    /** WO-ACL-16 criterion 2: legacy fields keep their meaning — no rename, no nulling. */
    @Transactional
    @Test
    void legacyContract_isUnchanged() throws Exception {
        ProcessDefinition model = deploy("test-gateway-no-default.bpmn");
        UUID processInstanceId = startWithIncident(model);
        Incident incident = openIncidentOf(processInstanceId);

        assertThat(incident.getActivityId()).isNotNull();
        assertThat(incident.getActivityId()).isNotEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        assertThat(incident.getMessage()).isNotBlank();
        assertThat(incident.getCreatedAt()).isNotNull();
        assertThat(incident.getCompletedAt()).isNull();
        // activityId stays the ACTIVITY uuid, not the model element id
        assertThat(incident.getActivityId().toString()).isNotEqualTo(incident.getBpmnElementId());
        assertThat(incident.getId()).isNotNull();
    }

    /** WO-ACL-16 criterion 3: an incident whose activity/instance/definition is gone yields
     *  empty enrichment — no 500. The prod schema keeps FK constraints, so a dangling activity
     *  reference can only appear through out-of-band data maintenance (manual cleanup, partial
     *  restore); the test emulates exactly that state by inserting the incident with FK checks
     *  temporarily disabled. */
    @Order(1)
    @Transactional
    @Test
    void deletedInstance_incidentStillReturned_withEmptyContext() throws Exception {
        entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        UUID danglingActivityId = UUID.randomUUID();
        IncidentEntity dangling = new IncidentEntity();
        dangling.setId(UUID.randomUUID());
        dangling.setActivityId(danglingActivityId);
        dangling.setMessage("orphan incident");
        dangling.setCreatedAt(Instant.now());
        incidentRepository.save(dangling);
        entityManager.flush();
        entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        entityManager.clear();

        IncidentQuery query = new IncidentQuery();
        query.setId(dangling.getId());
        PagedDataDTO<Incident> page = queryService.findIncidents(query, null);

        assertThatCode(() -> queryService.getIncident(dangling.getId())).doesNotThrowAnyException();
        assertThat(page.getData()).hasSize(1);
        Incident degraded = page.getData().get(0);
        assertThat(degraded.getProcessName()).isNull();
        assertThat(degraded.getProcessInstanceId()).isNull();
        assertThat(degraded.getBpmnElementId()).isNull();
        assertThat(degraded.getElementName()).isNull();
        // legacy fields survive
        assertThat(degraded.getMessage()).isEqualTo("orphan incident");
        assertThat(degraded.getCreatedAt()).isNotNull();
    }

    /**
     * WO-QW-6 (NEW3-06) criterion a: the orphan above must not leak into a
     * sibling global scan. This test does a bare {@code findAll()} over the
     * incidents table (same shape as
     * {@code GatewayNoDefaultIntegrationTests} — no spec, no filter) and asserts
     * no {@code 'orphan incident'} row is visible — whatever the class/method
     * order (the orphan test and this one share the JVM/database).
     *
     * <p>POF anchor (G-N): with the {@code REQUIRES_NEW} cleanup in
     * {@code @AfterEach} removed, run
     * {@code deletedInstance_incidentStillReturned_withEmptyContext} first and
     * this test second — this test goes RED (orphan visible). With the cleanup —
     * GREEN in any order.
     */
    @Order(2)
    @Transactional
    @Test
    void globalIncidentScan_seesNoOrphanFromSiblingTest() {
        List<IncidentEntity> orphans = incidentRepository.findAll().stream()
            .filter(i -> "orphan incident".equals(i.getMessage()))
            .toList();
        assertThat(orphans)
            .as("sibling orphan-incident test must not leak rows into a global scan")
            .isEmpty();
    }

    /**
     * WO-ACL-16 criterion 4: paging twenty incidents must issue a fixed small number of SQL
     * statements, not one extra lookup per row. Twenty incidents spread across twenty distinct
     * definitions so no L1-cache row could mask a per-row lookup (same trick as
     * QueryServiceBulkLoadingIntegrationTests).
     *
     * <p>CI-flake fix (2026-09-16, детерминированный 9 вместо 8 на холодном GitLab-раннере):
     * измерение считал и побочный кэш {@code BpmnService} (Caffeine, key=pdId): каждый промах —
     * ровно +1 SELECT ({@code FileService.getFileBytes} → {@code findById}, доказано замером:
     * снесённый кэш даёт 25 = 5 + 20). Прогрев побочным стартом инстансов случаен: кэш ровно на
     * грани capacity (20 × 5МБ weigher = 100МБ cap), и сколько своих записей переживёт eviction
     * зависит от асинхронного maintenance Caffeine — локально 0 промахов, на CI стабильно 4.
     * Поэтому перед {@code statistics().clear()} кэш греется детерминированно — по одному
     * холостому чтению модели на каждый из 20 id (их SELECT'ы в замер не входят). Замер после
     * этого — всегда 5 (page + count + 3 batch), порог 8 с запасом. Настоящий N+1 дал бы ~60
     * и порогом не прикрывается. MAX_QUERIES не поднимался — ports-порог тот же.
     */
    @Transactional
    @Test
    void pagingTwentyIncidents_issuesFixedQueryCount() throws Exception {
        String template = Files.readString(Paths.get("src/test/files/test-gateway-no-default.bpmn"));
        List<UUID> definitionIds = new java.util.ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            String bpmn = template.replace("test-gateway-no-default", "incident-bulk-" + i);
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            definitionIds.add(model.getId());
            startWithIncident(model);
        }

        entityManager.flush();
        entityManager.clear();
        // Детерминированный прогрев BpmnService-кэша (см. javadoc выше): все 20 моделей
        // в кэше ДО сброса статистики, их загрузочные SELECT'ы в замер не входят.
        for (UUID definitionId : definitionIds) {
            bpmnService.getProcessDefinitionModelById(definitionId);
        }
        statistics().clear();
        IncidentQuery query = new IncidentQuery();
        query.setPageSize(N);
        assertThat(queryService.findIncidents(query, null).getData()).hasSize(N);

        assertThat(statistics().getPrepareStatementCount())
            .as("statistics must actually be collecting (counter > 0), else the N+1 check is vacuous")
            .isGreaterThan(0);
        assertThat(statistics().getPrepareStatementCount())
            .as("query count must not scale with page size (N+1 regression)")
            .isLessThanOrEqualTo(MAX_QUERIES);
    }
}