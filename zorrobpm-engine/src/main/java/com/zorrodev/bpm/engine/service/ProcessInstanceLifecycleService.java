package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * WO-DEBT-7 S10: JPA-backed process-instance lifecycle reads and writes, moved
 * verbatim out of the REST-layer {@code ProcessInstanceRuntimeOperationsImpl}
 * (itself extracted from {@code RuntimeResource} in WO-DEBT-3). The REST class
 * stays behind as a thin facade: auth checks + delegation, with
 * {@code @Transactional} kept exactly where it was. Every JPA read and every
 * {@code .save()} lives here, inside the caller's transaction (no
 * {@code @Transactional} of its own — same as the original location, proven by
 * {@code ProcessInstanceRuntimeTransactionalIT}: audit failure rolls the save
 * back — same precedent as Slices 4, 6, 7).
 *
 * <p>Responsibility: the process-instance registry's small JPA slice that the
 * start-flow needs — resolving a definition key from a DTO (when the DTO carries
 * only an id) and persisting the initiator marker after the runtime has created
 * the instance. Deliberately NOT inside {@code RuntimeSupportService}
 * (that one resolves target definitions for validation and supports generic
 * runtime checks, not initiator bookkeeping) and NOT inside
 * {@code ProcessMemberService}/{@code ApiKeyService} etc. — merging would be
 * god-class drift (this file's class already exists to avoid stuffing
 * everything into RuntimeResource/RuntimeSupportService).
 */
@Component
@RequiredArgsConstructor
public class ProcessInstanceLifecycleService {

    private final ProcessDefinitionRepository processDefinitionRepository;

    /**
     * Resolves the definitionKey for the START auth check from the DTO.
     * If the DTO already carries a key it is returned as-is; if it carries only
     * a definition id the key is looked up via {@code processDefinitionRepository}.
     * Mirrors the original facade body byte-for-byte (null handling included).
     */
    public String resolveDefinitionKey(StartProcessInstanceDTO dto) {
        String definitionKey = dto.getProcessDefinitionKey();
        if (definitionKey == null && dto.getProcessDefinitionId() != null) {
            ProcessDefinitionEntity pd = processDefinitionRepository.findById(dto.getProcessDefinitionId()).orElse(null);
            if (pd != null) definitionKey = pd.getKey();
        }
        return definitionKey;
    }

    // recordInitiator удалён в WO-API-1 (HOLД-находка CTO): после
    // перевода на атомарный create(start, initiator) вызывающих 0,
    // оставлять ловушку P-14 нельзя.
}
