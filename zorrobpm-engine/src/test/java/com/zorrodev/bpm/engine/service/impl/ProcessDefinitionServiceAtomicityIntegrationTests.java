package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.BpmnRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WO-DEBT-5a — transactional atomicity (WO-REL-15 R-05). Real DB + real impl/repos/
 * parse/file/cache; only the mid-artifact failure itself (DBService) is mocked, and the
 * cache seam (BpmnService) is mocked for a consistency check. (The never() below is
 * consistency-only: the failure happens before the cache line, so it would pass under
 * both designs. The positive cache proof lives in the characterization class + the
 * order-pinning class.)
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class ProcessDefinitionServiceAtomicityIntegrationTests {

    @Autowired private ProcessDefinitionService service;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private BpmnRepository bpmnRepository;
    @MockitoBean private DBService dbService;
    @MockitoBean private BpmnService bpmnService;

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String messageStartBpmn(String key, String messageName) throws Exception {
        return Files.readString(Path.of("src/test/files/test-rel15-msg-deploy.bpmn"))
            .replace("rel15-msg-deploy", key)
            .replace("rel15-msg-received", messageName);
    }

    @Test
    void addProcessDefinition_midArtifactFailure_rollsBackEverything() throws Exception {
        String key = uniq("pdx");
        String msg = "msg-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = messageStartBpmn(key, msg);
        // Fail in the MIDDLE of the artifact set (after version save + file save)
        doThrow(new RuntimeException("forced mid-artifacts failure"))
            .when(dbService).createMessageStartSubscription(anyString(), any(), anyString(), anyString());

        assertThatThrownBy(() -> service.addProcessDefinition(bpmn))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("forced mid-artifacts failure");

        // WO-REL-15 R-05: nothing partial remains — no version row, no file row...
        long rows = processDefinitionRepository.findAll().stream()
            .filter(e -> key.equals(e.getKey())).count();
        assertThat(rows).as("no half-deployed version row").isZero();
        // WO-QW-4 (NEW-14): собственный признак вместо глобального count():
        // глобальный count() в общем контексте флейкал в CI (pipeline 171975),
        // а чужие строки других тестов здесь ни при чём. Наш BPMN несёт key
        // в XML — считаем только свои строки.
        long ownFiles = bpmnRepository.findAll().stream()
            .filter(e -> e.getBpmn() != null && e.getBpmn().contains(key)).count();
        assertThat(ownFiles).as("no orphaned BPMN file row for this deploy").isZero();
        // ...and the model cache was never filled (afterCommit never fired on rollback)
        verify(bpmnService, never()).addProcessDefinition(any(), any());
    }
}
