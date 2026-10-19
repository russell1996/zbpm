package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.FileService;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-C8-56: a model whose FEEL the engine's parser rejects can no longer be deployed through
 * {@code ProcessDefinitionService.addProcessDefinition} — that is the whole point of the
 * deploy-time gate. Tests that prove RUNTIME behaviour on such a model (the incident path,
 * the parked-join semantics) still need the definition to exist: after the gate that state is
 * only reachable for definitions deployed BEFORE the gate, i.e. "legacy" definitions.
 *
 * <p>This helper writes exactly the rows a pre-gate deploy would have written — through the REAL
 * parser and the REAL repositories (nothing mocked, no gate bypass in production code):
 * the {@code process_definitions} version row, the stored BPMN (the runtime re-parses it on
 * every model load) and the {@code process} registry row. The gate is bypassed by CONSTRUCTION
 * (the rows are written directly), never by a flag.
 */
public final class LegacyDefinitionDeployer {

    private LegacyDefinitionDeployer() {
    }

    public static UUID deployLegacy(String bpmn,
                                    BpmnParseService parseService,
                                    ProcessDefinitionRepository definitionRepository,
                                    ProcessRepository processRepository,
                                    FileService fileService) {
        // parsing is NOT the point of these fixtures — the XML is well-formed, the BREAKAGE is
        // in the FEEL the runtime will try to EVALUATE. The parser must succeed here or the
        // "legacy definition" premise is false.
        BpmnProcessDefinitionModel model = parseService.parse(bpmn);

        UUID id = UUID.randomUUID();
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(id);
        pd.setKey(model.getKey());
        pd.setName(model.getName());
        pd.setVersion(1);
        // unique per row (uk on sha256); the content hash is irrelevant to the runtime load path
        pd.setSha256(UUID.randomUUID().toString());
        pd.setCreatedAt(Instant.now());
        pd.setDeploymentState(ProcessDefinitionEntity.STATE_ACTIVE);
        definitionRepository.save(pd);

        // the runtime (BpmnServiceImpl.getProcessDefinitionModelById) re-parses the STORED xml —
        // without this row the definition would be a ghost
        fileService.saveFile(id, bpmn);

        processRepository.findByDefinitionKey(model.getKey()).orElseGet(() -> {
            ProcessEntity process = new ProcessEntity();
            process.setId(UUID.randomUUID());
            process.setDefinitionKey(model.getKey());
            process.setName(model.getName() != null ? model.getName() : model.getKey());
            process.setCreatedAt(Instant.now());
            return processRepository.save(process);
        });
        return id;
    }
}
