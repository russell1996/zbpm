package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * WO-DEBT-5a §6 (minimum): the model cache must be filled LAST — after every artifact
 * write — never before them.
 *
 * <p>Why this shape and not a direct "afterCommit vs direct call" probe: Spring fires
 * afterCommit callbacks BEFORE tx cleanup, so no runtime flag distinguishes a deferred
 * put from a direct one (verified empirically: isActualTransactionActive() is true in
 * both cases), and the cache line is last in the method so no reachable failure
 * separates the designs either. This test pins the closest OBSERVABLE property — cache
 * put strictly after all writes — the rollback test proves nothing is cached on
 * failure, and the registerSynchronization mechanism itself is cited from code
 * (ProcessDefinitionServiceImpl:236-247), labeled as reading, not proof.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ProcessDefinitionServiceCacheOrderIntegrationTests {

    @Autowired private ProcessDefinitionService service;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @MockitoBean private FileService fileService;
    @MockitoBean private DBService dbService;
    @MockitoBean private BpmnService bpmnService;

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void addProcessDefinition_cacheFilledStrictlyAfterAllWrites() throws Exception {
        String key = uniq("pdc");
        String msg = "msg-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = Files.readString(Path.of("src/test/files/test-rel15-msg-deploy.bpmn"))
            .replace("rel15-msg-deploy", key)
            .replace("rel15-msg-received", msg);

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        assertThat(result.getVersion()).isEqualTo(1);
        var inOrder = org.mockito.Mockito.inOrder(fileService, dbService, bpmnService);
        inOrder.verify(fileService).saveFile(any(), anyString());
        inOrder.verify(dbService).createMessageStartSubscription(anyString(), any(), anyString(), anyString());
        inOrder.verify(bpmnService).addProcessDefinition(any(), any());
        processDefinitionRepository.deleteById(result.getId());
    }
}
