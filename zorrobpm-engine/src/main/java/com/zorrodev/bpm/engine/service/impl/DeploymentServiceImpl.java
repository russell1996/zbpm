package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.DeployedDecisionDTO;
import com.zorrodev.bpm.contract.dto.DeployedProcessDTO;
import com.zorrodev.bpm.contract.dto.DeploymentDTO;
import com.zorrodev.bpm.contract.dto.DeploymentItemDTO;
import com.zorrodev.bpm.contract.dto.DeploymentResourceType;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.DeploymentEntity;
import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DeploymentRepository;
import com.zorrodev.bpm.engine.repository.DmnDefinitionRepository;
import com.zorrodev.bpm.engine.service.DeploymentService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * WO-C8-18: atomic multi-resource deployments. Owns the batch transaction; the per-resource
 * work reuses the existing single-deploy paths (same code, same validation, same versioning),
 * only stamped with the batch id. Single deploys keep passing {@code null} and stay
 * byte-identical.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentServiceImpl implements DeploymentService {

    private final TransactionTemplate transactionTemplate;
    private final DeploymentRepository deploymentRepository;
    private final DmnDefinitionRepository dmnDefinitionRepository;
    private final ProcessDefinitionService processDefinitionService;
    private final DmnService dmnService;

    @Override
    public DeploymentDTO deployBatch(List<DeploymentItemDTO> resources, String description, String deployedBy) {
        if (resources == null || resources.isEmpty()) {
            throw new EngineException("Deployment batch is empty — nothing to deploy");
        }
        for (int i = 0; i < resources.size(); i++) {
            DeploymentItemDTO item = resources.get(i);
            if (item == null || item.getType() == null) {
                throw new EngineException("Deployment resource #" + i + " has no type (expected BPMN or DMN)");
            }
            if (item.getContent() == null || item.getContent().isBlank()) {
                throw new EngineException("Deployment resource #" + i + " has empty content");
            }
        }
        return transactionTemplate.execute(status -> {
            DeploymentEntity deployment = new DeploymentEntity();
            deployment.setId(UUID.randomUUID());
            deployment.setCreatedAt(Instant.now());
            deployment.setDeployedBy(deployedBy);
            deployment.setDescription(description);
            deploymentRepository.save(deployment);
            return deployBatchItems(deployment, resources);
        });
    }

    private DeploymentDTO deployBatchItems(DeploymentEntity deployment, List<DeploymentItemDTO> resources) {
        List<DeployedProcessDTO> processes = new ArrayList<>();
        for (DeploymentItemDTO item : resources) {
            if (item.getType() != DeploymentResourceType.BPMN) {
                continue;
            }
            // Joins this transaction (REQUIRED): a later failure rolls the version back.
            ProcessDefinition pd = processDefinitionService.addProcessDefinition(item.getContent(), deployment.getId());
            DeployedProcessDTO deployed = new DeployedProcessDTO();
            deployed.setProcessDefinitionId(pd.getId());
            deployed.setKey(pd.getKey());
            deployed.setVersion(pd.getVersion());
            processes.add(deployed);
        }
        UUID firstProcessId = processes.isEmpty() ? null : processes.get(0).getProcessDefinitionId();

        List<DeployedDecisionDTO> decisions = new ArrayList<>();
        for (DeploymentItemDTO item : resources) {
            if (item.getType() != DeploymentResourceType.DMN) {
                continue;
            }
            // Joins this transaction as well; EngineException (malformed DMN, no decisions)
            // aborts the whole batch — the resource maps it to 400 like the single DMN path.
            dmnService.deploy(item.getContent(), firstProcessId, deployment.getId());
        }
        // Report exactly this batch's rows (unique batch id — concurrent deploys cannot leak in).
        List<DmnDefinitionEntity> rows = dmnDefinitionRepository.findByDeploymentId(deployment.getId());
        rows.sort(Comparator.comparing(DmnDefinitionEntity::getDecisionId));
        for (DmnDefinitionEntity row : rows) {
            DeployedDecisionDTO deployed = new DeployedDecisionDTO();
            deployed.setDecisionId(row.getDecisionId());
            deployed.setVersion(row.getVersion());
            decisions.add(deployed);
        }

        log.info("Deployment {} laid down {} processes and {} decisions", deployment.getId(), processes.size(), decisions.size());
        DeploymentDTO dto = new DeploymentDTO();
        dto.setId(deployment.getId());
        dto.setCreatedAt(deployment.getCreatedAt());
        dto.setProcesses(processes);
        dto.setDecisions(decisions);
        return dto;
    }
}
