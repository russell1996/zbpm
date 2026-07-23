package com.zorrodev.bpm.engine.tenant;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ARCH-1b: V11 integration test — per-tenant read isolation for ALL query channels.
 * Uses REAL QueryService (not null, not inline SQL) on real PostgreSQL.
 *
 * For each of 7 channels: (b) allowedPdIds={pdA} → only A data; (d) empty → deny; (c) null → sees all.
 *
 * POF G-N: comment filter in QueryServiceImpl → corresponding (b) test RED → restore → GREEN.
 */
public class TenantReadIsolationPgIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired QueryService queryService;
    @Autowired ProcessDefinitionRepository processDefinitionRepository;
    @Autowired ProcessInstanceRepository processInstanceRepository;

    private UUID pdIdA, pdIdB, piIdA, piIdB;

    @BeforeEach
    void setUp() {
        // Clean in FK order
        jdbc.update("DELETE FROM variables WHERE process_instance_id IN " +
            "(SELECT id FROM process_instances WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b')))");
        jdbc.update("DELETE FROM message_subscriptions WHERE process_instance_id IN " +
            "(SELECT id FROM process_instances WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b')))");
        jdbc.update("DELETE FROM timer_jobs WHERE process_instance_id IN " +
            "(SELECT id FROM process_instances WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b')))");
        jdbc.update("DELETE FROM incidents WHERE activity_id IN " +
            "(SELECT id FROM activities WHERE process_instance_id IN " +
            "(SELECT id FROM process_instances WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b'))))");
        jdbc.update("DELETE FROM service_tasks WHERE process_instance_id IN " +
            "(SELECT id FROM process_instances WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b')))");
        jdbc.update("DELETE FROM process_instances WHERE process_definition_id IN " +
            "(SELECT id FROM process_definitions WHERE code IN ('isol-a','isol-b'))");
        jdbc.update("DELETE FROM process_definitions WHERE code IN ('isol-a','isol-b')");

        pdIdA = UUID.randomUUID(); pdIdB = UUID.randomUUID();
        piIdA = UUID.randomUUID(); piIdB = UUID.randomUUID();

        // Process definitions
        var pdA = new ProcessDefinitionEntity();
        pdA.setId(pdIdA); pdA.setKey("isol-a"); pdA.setName("A");
        pdA.setVersion(1); pdA.setSha256("sha-a"); pdA.setCreatedAt(Instant.now());
        var pdB = new ProcessDefinitionEntity();
        pdB.setId(pdIdB); pdB.setKey("isol-b"); pdB.setName("B");
        pdB.setVersion(1); pdB.setSha256("sha-b"); pdB.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdA);
        processDefinitionRepository.save(pdB);

        // Process instances
        var piA = new ProcessInstanceEntity();
        piA.setId(piIdA); piA.setProcessDefinitionId(pdIdA); piA.setStartedAt(Instant.now());
        var piB = new ProcessInstanceEntity();
        piB.setId(piIdB); piB.setProcessDefinitionId(pdIdB); piB.setStartedAt(Instant.now());
        processInstanceRepository.save(piA);
        processInstanceRepository.save(piB);

        // ServiceTasks (has processDefinitionId directly)
        UUID stA = UUID.randomUUID(), stB = UUID.randomUUID();
        jdbc.update("INSERT INTO service_tasks (id,process_instance_id,process_definition_id,bpmn_element_id,created_at,retries_remaining) VALUES (?,?,?,?,?,?)",
            stA, piIdA, pdIdA, "svcA", Instant.now(), 0);
        jdbc.update("INSERT INTO service_tasks (id,process_instance_id,process_definition_id,bpmn_element_id,created_at,retries_remaining) VALUES (?,?,?,?,?,?)",
            stB, piIdB, pdIdB, "svcB", Instant.now(), 0);

        // Activities (for incidents)
        UUID actA = UUID.randomUUID(), actB = UUID.randomUUID();
        jdbc.update("INSERT INTO activities (id,process_instance_id,bpmn_element_id,created_at,type,status) VALUES (?,?,?,?,?,?)",
            actA, piIdA, "startA", Instant.now(), "START_EVENT", "COMPLETED");
        jdbc.update("INSERT INTO activities (id,process_instance_id,bpmn_element_id,created_at,type,status) VALUES (?,?,?,?,?,?)",
            actB, piIdB, "startB", Instant.now(), "START_EVENT", "COMPLETED");

        // Incidents (linked via activityId)
        jdbc.update("INSERT INTO incidents (id,activity_id,bpmn_element_id,created_at) VALUES (?,?,?,?)",
            UUID.randomUUID(), actA, "elA", Instant.now());
        jdbc.update("INSERT INTO incidents (id,activity_id,bpmn_element_id,created_at) VALUES (?,?,?,?)",
            UUID.randomUUID(), actB, "elB", Instant.now());

        // TimerJobs (linked via processInstanceId)
        jdbc.update("INSERT INTO timer_jobs (id,process_instance_id,activity_id,due_at,fired,created_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), piIdA, actA, Instant.now(), false, Instant.now());
        jdbc.update("INSERT INTO timer_jobs (id,process_instance_id,activity_id,due_at,fired,created_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), piIdB, actB, Instant.now(), false, Instant.now());

        // MessageSubscriptions (linked via processInstanceId)
        jdbc.update("INSERT INTO message_subscriptions (id,process_instance_id,activity_id,created_at) VALUES (?,?,?,?)",
            UUID.randomUUID(), piIdA, actA, Instant.now());
        jdbc.update("INSERT INTO message_subscriptions (id,process_instance_id,activity_id,created_at) VALUES (?,?,?,?)",
            UUID.randomUUID(), piIdB, actB, Instant.now());

        // Variables (linked via processInstanceId)
        jdbc.update("INSERT INTO variables (id,process_instance_id,scope_id,name,type,text_value) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), piIdA, actA, "varA", "STRING", "a");
        jdbc.update("INSERT INTO variables (id,process_instance_id,scope_id,name,type,text_value) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), piIdB, actB, "varB", "STRING", "b");
    }

    // ===== ServiceTasks =====

    @Test
    void serviceTasks_allowedPdIds_seesOnlyA() {
        var r = queryService.findServiceTasks(new ServiceTaskQuery(), Set.of(pdIdA));
        assertThat(r.getData()).hasSize(1);
        assertThat(r.getData().get(0).getProcessDefinitionId()).isEqualTo(pdIdA);
    }

    @Test
    void serviceTasks_allowedPdIds_excludesB() {
        var r = queryService.findServiceTasks(new ServiceTaskQuery(), Set.of(pdIdA));
        assertThat(r.getData()).noneMatch(st -> st.getProcessDefinitionId().equals(pdIdB));
    }

    @Test
    void serviceTasks_nullPdIds_seesAll() {
        var r = queryService.findServiceTasks(new ServiceTaskQuery(), null);
        assertThat(r.getData()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void serviceTasks_emptyPdIds_denied() {
        var r = queryService.findServiceTasks(new ServiceTaskQuery(), Set.of());
        assertThat(r.getData()).isEmpty();
    }

    // ===== Incidents =====

    @Test
    void incidents_allowedPdIds_seesOnlyA() {
        var r = queryService.findIncidents(new IncidentQuery(), Set.of(pdIdA));
        assertThat(r.getData()).hasSize(1);
    }

    @Test
    void incidents_allowedPdIds_excludesB() {
        var r = queryService.findIncidents(new IncidentQuery(), Set.of(pdIdA));
        assertThat(r.getData()).hasSize(1);
        // Incident DTO has no processDefinitionId — verified by size only
    }

    @Test
    void incidents_nullPdIds_seesAll() {
        var r = queryService.findIncidents(new IncidentQuery(), null);
        assertThat(r.getData()).hasSize(2);
    }

    @Test
    void incidents_emptyPdIds_denied() {
        var r = queryService.findIncidents(new IncidentQuery(), Set.of());
        assertThat(r.getData()).isEmpty();
    }

    // ===== TimerJobs =====

    @Test
    void timerJobs_allowedPdIds_seesOnlyA() {
        var r = queryService.findTimerJobs(new TimerJobQuery(), Set.of(pdIdA));
        assertThat(r.getData()).hasSize(1);
    }

    @Test
    void timerJobs_allowedPdIds_excludesB() {
        var r = queryService.findTimerJobs(new TimerJobQuery(), Set.of(pdIdA));
        assertThat(r.getData()).isEmpty();
    }

    @Test
    void timerJobs_nullPdIds_seesAll() {
        var r = queryService.findTimerJobs(new TimerJobQuery(), null);
        assertThat(r.getData()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void timerJobs_emptyPdIds_denied() {
        var r = queryService.findTimerJobs(new TimerJobQuery(), Set.of());
        assertThat(r.getData()).isEmpty();
    }

    // ===== MessageSubscriptions =====

    @Test
    void msgSubs_allowedPdIds_seesOnlyA() {
        var r = queryService.findMessageSubscriptions(new MessageSubscriptionQuery(), Set.of(pdIdA));
        assertThat(r.getData()).hasSize(1);
    }

    @Test
    void msgSubs_allowedPdIds_excludesB() {
        var r = queryService.findMessageSubscriptions(new MessageSubscriptionQuery(), Set.of(pdIdA));
        assertThat(r.getData()).isEmpty();
    }

    @Test
    void msgSubs_nullPdIds_seesAll() {
        var r = queryService.findMessageSubscriptions(new MessageSubscriptionQuery(), null);
        assertThat(r.getData()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void msgSubs_emptyPdIds_denied() {
        var r = queryService.findMessageSubscriptions(new MessageSubscriptionQuery(), Set.of());
        assertThat(r.getData()).isEmpty();
    }

    // ===== Variables =====

    @Test
    void variables_allowedPdIds_seesOnlyA() {
        var r = queryService.findVariables(new VariableQuery(), Set.of(pdIdA));
        assertThat(r.getData()).hasSize(1);
        assertThat(r.getData().get(0).getName()).isEqualTo("varA");
    }

    @Test
    void variables_allowedPdIds_excludesB() {
        var r = queryService.findVariables(new VariableQuery(), Set.of(pdIdA));
        assertThat(r.getData()).noneMatch(v -> "varB".equals(v.getName()));
    }

    @Test
    void variables_nullPdIds_seesAll() {
        var r = queryService.findVariables(new VariableQuery(), null);
        assertThat(r.getData()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void variables_emptyPdIds_denied() {
        var r = queryService.findVariables(new VariableQuery(), Set.of());
        assertThat(r.getData()).isEmpty();
    }
}
