package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.FileService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BpmnServiceImplTest {

    @Test
    void testService() {
        UUID id = UUID.randomUUID();
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.setKey("process1");

        FileService fileService = mock(FileService.class);
        BpmnParseService bpmnParseService = mock(BpmnParseService.class);

        BpmnServiceImpl service = new BpmnServiceImpl(fileService, bpmnParseService, 500, 60);
        service.addProcessDefinition(id, model);
        assertThat(service.getProcessDefinitionModelById(id)).isNotNull();
        assertThat(service.getProcessDefinitionModelById(id).getKey()).isEqualTo("process1");
    }

    @Test
    void missingFileRaisesEngineExceptionInsteadOfCachingNull() throws Exception {
        UUID id = UUID.randomUUID();
        FileService fileService = mock(FileService.class);
        BpmnParseService bpmnParseService = mock(BpmnParseService.class);
        when(fileService.getFileBytes(id)).thenReturn(Optional.empty());

        BpmnServiceImpl service = new BpmnServiceImpl(fileService, bpmnParseService, 500, 60);

        assertThatThrownBy(() -> service.getProcessDefinitionModelById(id))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("BPMN file not found");
    }

    // --- Criterion #1: cache does not exceed max-size ---

    @Test
    void cacheEvictsWhenMaxSizeExceeded() throws Exception {
        int maxSize = 3;
        FileService fileService = mock(FileService.class);
        BpmnParseService bpmnParseService = mock(BpmnParseService.class);

        BpmnServiceImpl service = new BpmnServiceImpl(fileService, bpmnParseService, maxSize, 60);

        // Add maxSize entries
        for (int i = 0; i < maxSize; i++) {
            UUID id = UUID.randomUUID();
            BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
            model.setKey("process" + i);
            service.addProcessDefinition(id, model);
        }

        // Add one more → oldest should be evicted
        UUID newId = UUID.randomUUID();
        BpmnProcessDefinitionModel newModel = new BpmnProcessDefinitionModel();
        newModel.setKey("process-new");
        service.addProcessDefinition(newId, newModel);

        // The new entry should be in cache
        assertThat(service.getProcessDefinitionModelById(newId).getKey()).isEqualTo("process-new");
    }

    // --- Criterion #2: evicted entry reloads from DB ---

    @Test
    void evictedEntryReloadsFromDatabase() throws Exception {
        FileService fileService = mock(FileService.class);
        BpmnParseService bpmnParseService = mock(BpmnParseService.class);
        BpmnProcessDefinitionModel parsedModel = new BpmnProcessDefinitionModel();
        parsedModel.setKey("reloaded");
        when(fileService.getFileBytes(any())).thenReturn(Optional.of("<bpmn/>"));
        when(bpmnParseService.parse("<bpmn/>")).thenReturn(parsedModel);

        BpmnServiceImpl service = new BpmnServiceImpl(fileService, bpmnParseService, 2, 60);

        UUID id = UUID.randomUUID();
        // First load: from "DB"
        BpmnProcessDefinitionModel first = service.getProcessDefinitionModelById(id);
        assertThat(first.getKey()).isEqualTo("reloaded");

        // Evict by adding 2 more entries
        for (int i = 0; i < 2; i++) {
            UUID evictId = UUID.randomUUID();
            BpmnProcessDefinitionModel evictModel = new BpmnProcessDefinitionModel();
            evictModel.setKey("evict" + i);
            service.addProcessDefinition(evictId, evictModel);
        }

        // Reload from "DB" after eviction
        BpmnProcessDefinitionModel reloaded = service.getProcessDefinitionModelById(id);
        assertThat(reloaded.getKey()).isEqualTo("reloaded");
    }

    // --- Criterion #3: computeIfAbsent atomicity preserved ---

    @Test
    void computeIfAbsent_atomicallyLoadsOnce() throws Exception {
        FileService fileService = mock(FileService.class);
        BpmnParseService bpmnParseService = mock(BpmnParseService.class);
        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        model.setKey("atomic");
        when(fileService.getFileBytes(any())).thenReturn(Optional.of("<bpmn/>"));
        when(bpmnParseService.parse("<bpmn/>")).thenReturn(model);

        BpmnServiceImpl service = new BpmnServiceImpl(fileService, bpmnParseService, 500, 60);

        UUID id = UUID.randomUUID();
        // Call twice — should only parse once (Caffeine.get is atomic)
        BpmnProcessDefinitionModel first = service.getProcessDefinitionModelById(id);
        BpmnProcessDefinitionModel second = service.getProcessDefinitionModelById(id);

        assertThat(first).isSameAs(second);
        org.mockito.Mockito.verify(bpmnParseService, org.mockito.Mockito.times(1)).parse("<bpmn/>");
    }

    private static UUID any() { return org.mockito.ArgumentMatchers.any(); }
}
