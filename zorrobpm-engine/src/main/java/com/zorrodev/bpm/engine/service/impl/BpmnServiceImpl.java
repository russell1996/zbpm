package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class BpmnServiceImpl implements BpmnService {

    private final Map<UUID, BpmnProcessDefinitionModel> models = new ConcurrentHashMap<>();

    private final FileService fileService;
    private final BpmnParseService bpmnParseService;

    @Override
    public void addProcessDefinition(UUID id, BpmnProcessDefinitionModel model) {
        models.put(id, model);
    }

    @Override
    public BpmnProcessDefinitionModel getProcessDefinitionModelById(UUID id) {
        // computeIfAbsent loads-and-parses atomically: two threads racing on the same id load the model
        // once, and a parse failure / missing file surfaces as a clear exception instead of caching null.
        return models.computeIfAbsent(id, uuid -> {
            String bpmn;
            try {
                bpmn = fileService.getFileBytes(uuid);
            } catch (IOException e) {
                throw new EngineException("Failed to read BPMN file for definition " + uuid, e);
            }
            if (bpmn == null) {
                throw new EngineException("BPMN file not found for definition " + uuid);
            }
            return bpmnParseService.parse(bpmn);
        });
    }
}
