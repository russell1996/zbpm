package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.FileService;
import org.junit.jupiter.api.Test;

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

        BpmnServiceImpl service = new BpmnServiceImpl(fileService, bpmnParseService);
        service.addProcessDefinition(id, model);
        assertThat(service.getProcessDefinitionModelById(id)).isNotNull();
        assertThat(service.getProcessDefinitionModelById(id).getKey()).isEqualTo("process1");
    }

    @Test
    void missingFileRaisesEngineExceptionInsteadOfCachingNull() throws Exception {
        UUID id = UUID.randomUUID();
        FileService fileService = mock(FileService.class);
        BpmnParseService bpmnParseService = mock(BpmnParseService.class);
        when(fileService.getFileBytes(id)).thenReturn(null);

        BpmnServiceImpl service = new BpmnServiceImpl(fileService, bpmnParseService);

        assertThatThrownBy(() -> service.getProcessDefinitionModelById(id))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("BPMN file not found");
    }
}
