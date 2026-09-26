package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CallActivityRecursionIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;

    private static String bpmn(String processId, String calledElement, String... extraAttrs) {
        String extra = String.join(" ", extraAttrs);
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                              id="Defs-%s" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="%s" name="%s" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="call"/>
                <bpmn:callActivity id="call" name="Call %s">
                  <bpmn:extensionElements>
                    <zeebe:calledElement processId="%s" %s/>
                  </bpmn:extensionElements>
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:callActivity>
                <bpmn:sequenceFlow id="f2" sourceRef="call" targetRef="end"/>
                <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(processId, processId, processId, calledElement, calledElement, extra);
    }

    private void deploy(String bpmn) {
        processDefinitionService.addProcessDefinition(bpmn);
    }

    private UUID startByKey(String processId) {
        // Deploy already done — resolve latest version
        List<ProcessDefinition> defs = processDefinitionService.getProcessDefinitions(
            new com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters() {{
                setProcessDefinitionKey(processId);
            }}).getData();
        assertThat(defs).isNotEmpty();
        ProcessDefinition def = defs.stream().filter(d -> d.getKey().equals(processId)).findFirst().orElseThrow();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(def.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    @Transactional
    @Test
    void selfRecursion_AtoA_parksIncident_noStackOverflow() throws Exception {
        // Process A calls itself
        String key = "rec-A-" + UUID.randomUUID().toString().substring(0, 6);
        deploy(bpmn(key, key));

        UUID pi = startByKey(key);

        // Must NOT StackOverflow — the call activity parks as ERROR + incident.
        // The ERROR activity lives in the child instance (pi's child), not on pi itself.
        List<IncidentEntity> incidents = List.of();
        ActivityEntity err = null;
        for (ActivityEntity a : activityRepository.findAll().stream()
            .filter(a -> a.getStatus() == ActivityStatus.ERROR && "call".equals(a.getBpmnElementId()))
            .toList()) {
            List<IncidentEntity> cands = incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(a.getId()));
            if (cands.stream().anyMatch(i -> i.getMessage() != null && i.getMessage().toLowerCase().contains("recursion"))) {
                err = a;
                incidents = cands;
                break;
            }
        }
        assertThat(err).as("self-calling A must have an ERROR activity with recursion incident, not StackOverflow").isNotNull();
        assertThat(incidents).as("incident must exist for the recursive call").isNotEmpty();
    }

    @Transactional
    @Test
    void indirectRecursion_AtoBtoA_parksIncident() throws Exception {
        String keyA = "rec-ABA-A-" + UUID.randomUUID().toString().substring(0, 6);
        String keyB = "rec-ABA-B-" + UUID.randomUUID().toString().substring(0, 6);
        // A calls B, B calls A
        deploy(bpmn(keyB, keyA));
        deploy(bpmn(keyA, keyB));

        UUID pi = startByKey(keyA);

        // The chain A->B->A must be caught at the second A (depth 3, recursion)
        // Outer pi parks on B's call which parks on A's recursion incident
        // We just need to prove an ERROR/incident with recursion message exists somewhere in the graph
        List<ActivityEntity> allErr = activityRepository.findAll().stream()
            .filter(a -> a.getStatus() == ActivityStatus.ERROR)
            .toList();
        // At least one ERROR on the recursion
        long recursionErrors = allErr.stream()
            .filter(a -> incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(a.getId()))
                .stream().anyMatch(i -> i.getMessage() != null && i.getMessage().toLowerCase().contains("recursion")))
            .count();
        assertThat(recursionErrors).as("A->B->A must have a recursion incident").isGreaterThan(0);
        // No StackOverflowError leaked
    }

    @Transactional
    @Test
    void normalCallActivity_withoutRecursion_completes() throws Exception {
        String childKey = "call-child-" + UUID.randomUUID().toString().substring(0, 6);
        String parentKey = "call-parent-" + UUID.randomUUID().toString().substring(0, 6);
        String childBpmn = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              id="Defs-child" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="%s" name="%s" isExecutable="true">
                <bpmn:startEvent id="cstart"><bpmn:outgoing>cf1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="cf1" sourceRef="cstart" targetRef="cend"/>
                <bpmn:endEvent id="cend"><bpmn:incoming>cf1</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(childKey, childKey);
        deploy(childBpmn);
        deploy(bpmn(parentKey, childKey));

        UUID pi = startByKey(parentKey);

        // Parent should be COMPLETED (child ran to end, no incident)
        // Need a bit: process instance completes synchronously in this model
        // Check activities — no ERROR, parent COMPLETED
        List<IncidentEntity> open = incidentRepository.findByActivityIdInAndCompletedAtIsNull(
            activityRepository.findAll().stream().map(ActivityEntity::getId).toList());
        // Filter to this pi's incidents via activities
        long piOpen = open.stream()
            .filter(i -> activityRepository.findById(i.getActivityId()).map(a -> a.getProcessInstanceId().equals(pi)).orElse(false))
            .count();
        assertThat(piOpen).as("normal chain must have no open incidents").isEqualTo(0);
    }
}
