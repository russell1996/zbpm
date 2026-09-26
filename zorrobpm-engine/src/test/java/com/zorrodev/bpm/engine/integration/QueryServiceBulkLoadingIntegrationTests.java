package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-PERF-2 (D-01) regression: paging N process-instances/user-tasks/service-tasks must issue a
 * fixed, small number of SQL statements — not one extra lookup per row (the N+1 this WO fixes in
 * ProcessInstanceMapper/UserTaskMapper/ServiceTaskMapper).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class QueryServiceBulkLoadingIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @PersistenceContext
    private EntityManager entityManager;

    private static final int N = 20;
    // findAll (page) + findAll (count) + ONE batch loader (process-definition / activity
    // lookup) — matches WO-PERF-2 acceptance criterion #1 ("<=3, not one extra per row").
    private static final long MAX_QUERIES = 3;

    /**
     * WO-PERF-4: Hibernate statement statistics are enabled programmatically instead of via
     * {@code @TestPropertySource(spring.jpa.properties.hibernate.generate_statistics=true)} —
     * that property created a dedicated Spring context for this single test class (one extra
     * full Spring startup per run). The counter works exactly the same either way.
     */
    @BeforeEach
    void enableStatistics() {
        statistics().setStatisticsEnabled(true);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private ProcessDefinition deploy(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        return processDefinitionService.addProcessDefinition(bpmn);
    }

    private void startInstances(ProcessDefinition model, int count) {
        for (int i = 0; i < count; i++) {
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            runtimeService.startProcessInstance(dto);
        }
    }

    /**
     * Starts N instances spread across N distinct process definitions (each a uniquely-keyed
     * copy of the template) so every row's processDefinitionId differs — otherwise the L1 cache
     * would serve every row after the first from the same cached definition and mask the N+1.
     */
    private void startInstancesAcrossDistinctDefinitions(String templateFile, int count) throws Exception {
        String template = Files.readString(Paths.get("src/test/files/" + templateFile));
        for (int i = 0; i < count; i++) {
            String bpmn = template.replace("test-usertask-query", "bulk-load-pi-" + i);
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            runtimeService.startProcessInstance(dto);
        }
    }

    @Transactional
    @Test
    void pagingProcessInstancesIssuesFixedQueryCount() throws Exception {
        startInstancesAcrossDistinctDefinitions("test-usertask-query.bpmn", N);

        // detach everything created above so the query below can't be served from the L1
        // cache — it must issue real SQL, the same as a fresh request-scoped session would.
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setPageSize(N);
        assertThat(queryService.findProcessInstances(query, null).getData()).hasSize(N);

        assertThat(statistics().getPrepareStatementCount())
            .as("statistics must actually be collecting (counter > 0), else the N+1 check is vacuous")
            .isGreaterThan(0);
        assertThat(statistics().getPrepareStatementCount())
            .as("query count must not scale with page size (N+1 regression)")
            .isLessThanOrEqualTo(MAX_QUERIES);
    }

    @Transactional
    @Test
    void pagingUserTasksIssuesFixedQueryCount() throws Exception {
        ProcessDefinition model = deploy("test-usertask-query.bpmn");
        startInstances(model, N);

        // detach everything created above so the query below can't be served from the L1
        // cache — it must issue real SQL, the same as a fresh request-scoped session would.
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
        UserTaskQuery query = new UserTaskQuery();
        query.setPageSize(N);
        assertThat(queryService.findUserTasks(query, null).getData()).hasSize(N);

        assertThat(statistics().getPrepareStatementCount())
            .as("statistics must actually be collecting (counter > 0), else the N+1 check is vacuous")
            .isGreaterThan(0);
        assertThat(statistics().getPrepareStatementCount())
            .as("query count must not scale with page size (N+1 regression)")
            .isLessThanOrEqualTo(MAX_QUERIES);
    }

    @Transactional
    @Test
    void pagingServiceTasksIssuesFixedQueryCount() throws Exception {
        ProcessDefinition model = deploy("test-service-task-fail.bpmn");
        startInstances(model, N);

        // detach everything created above so the query below can't be served from the L1
        // cache — it must issue real SQL, the same as a fresh request-scoped session would.
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setPageSize(N);
        assertThat(queryService.findServiceTasks(query, null).getData()).hasSize(N);

        assertThat(statistics().getPrepareStatementCount())
            .as("statistics must actually be collecting (counter > 0), else the N+1 check is vacuous")
            .isGreaterThan(0);
        assertThat(statistics().getPrepareStatementCount())
            .as("query count must not scale with page size (N+1 regression)")
            .isLessThanOrEqualTo(MAX_QUERIES);
    }
}
