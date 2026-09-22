package com.zorrodev.bpm.engine.deployment;

import com.zorrodev.bpm.contract.dto.DeploymentItemDTO;
import com.zorrodev.bpm.contract.dto.DeploymentResourceType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.service.DeploymentService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WO-ENG-18: a process deployed through the ordinary batch path
 * ({@code DeploymentService.deployBatch}, i.e. {@code POST /deployments} — NOT the
 * submission/approval flow) must own exactly one row in the {@code process} registry,
 * so {@code archiveProcess}/{@code unarchiveProcess} work on it instead of 404-ing.
 *
 * <p>Calls the REAL services + repository (G-N): {@code DeploymentServiceImpl} →
 * {@code ProcessDefinitionServiceImpl.addProcessDefinition} → {@code ProcessRepository}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ProcessRegistryDirectDeployIntegrationTest {

    @Autowired
    private DeploymentService deploymentService;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private ProcessRepository processRepository;

    private static String bpmnFor(String key) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/dummy-process.bpmn"));
        return bpmn.replace("dummy-process", key).replace("Dummy Process", key);
    }

    private static DeploymentItemDTO bpmnItem(String bpmn) {
        DeploymentItemDTO item = new DeploymentItemDTO();
        item.setType(DeploymentResourceType.BPMN);
        item.setContent(bpmn);
        return item;
    }

    @Transactional
    @Test
    void batchDeploy_newKey_createsExactlyOneProcessRow() throws Exception {
        String key = "eng18-direct-" + UUID.randomUUID().toString().substring(0, 8);

        deploymentService.deployBatch(List.of(bpmnItem(bpmnFor(key))), "eng18", "eng18");

        var row = processRepository.findByDefinitionKey(key);
        assertThat(row).isPresent();
        assertThat(row.get().getDefinitionKey()).isEqualTo(key);
        assertThat(row.get().isArchived()).isFalse();
        long rows = processRepository.findAll().stream()
            .filter(p -> key.equals(p.getDefinitionKey()))
            .count();
        assertThat(rows).isEqualTo(1);
    }

    @Transactional
    @Test
    void archive_afterBatchDeploy_doesNotThrow_andFlipsFlag() throws Exception {
        String key = "eng18-arch-" + UUID.randomUUID().toString().substring(0, 8);
        deploymentService.deployBatch(List.of(bpmnItem(bpmnFor(key))), "eng18", "eng18");

        // Pre-fix this throws ResponseStatusException 404 "Process not found: <key>".
        assertThatCode(() -> processDefinitionService.archiveProcess(key))
            .doesNotThrowAnyException();
        assertThat(processRepository.findByDefinitionKey(key))
            .isPresent()
            .hasValueSatisfying(p -> {
                assertThat(p.isArchived()).isTrue();
                assertThat(p.getArchivedAt()).isNotNull();
            });

        assertThatCode(() -> processDefinitionService.unarchiveProcess(key))
            .doesNotThrowAnyException();
        assertThat(processRepository.findByDefinitionKey(key))
            .isPresent()
            .hasValueSatisfying(p -> {
                assertThat(p.isArchived()).isFalse();
                assertThat(p.getArchivedAt()).isNull();
            });
    }

    @Transactional
    @Test
    void batchDeploy_newVersionOfSameKey_doesNotDuplicateProcessRow() throws Exception {
        String key = "eng18-ver-" + UUID.randomUUID().toString().substring(0, 8);
        var v1 = deploymentService.deployBatch(
            List.of(bpmnItem(bpmnFor(key))), "eng18", "eng18")
            .getProcesses().get(0);
        assertThat(v1.getVersion()).isEqualTo(1);

        // Same key, different content (renamed display label only — the process id
        // stays identical) → new version, same registry row.
        String v2bpmn = bpmnFor(key).replace("name=\"" + key + "\"", "name=\"" + key + " v2\"");
        var v2 = deploymentService.deployBatch(
            List.of(bpmnItem(v2bpmn)), "eng18", "eng18")
            .getProcesses().get(0);
        assertThat(v2.getKey()).isEqualTo(key);
        assertThat(v2.getVersion()).isEqualTo(2);

        long rows = processRepository.findAll().stream()
            .filter(p -> key.equals(p.getDefinitionKey()))
            .count();
        assertThat(rows).isEqualTo(1);
    }
}
