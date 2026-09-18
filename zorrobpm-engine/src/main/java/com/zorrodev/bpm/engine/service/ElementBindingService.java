package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.ElementBindingDTO;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-7 S6: JPA-backed element↔artifact binding lifecycle, moved verbatim
 * out of the REST-layer {@code ElementBindingOperationsImpl} (WO-DEBT-4c
 * slice, itself a byte-for-byte move from {@code FormResource}). The REST
 * class stays behind as a thin facade: auth checks + delegation, with
 * {@code @Transactional} kept exactly where it was
 * ({@code createElementBinding}/{@code deleteElementBinding}). Every read and
 * every {@code .save()}/{@code .delete()} lives here, inside the caller's
 * transaction (no {@code @Transactional} of its own — same as the original
 * location, proven by {@code ElementBindingTransactionIT}: a save failure
 * rolls the upsert-delete back).
 *
 * <p>Responsibility: binding a process element to a form artifact (upsert with
 * version pinning, listing, deletion). Deliberately NOT inside
 * {@code SchemaMapService} — same three repositories, different aggregate:
 * that one inventories and versions form schemas, this one links elements to
 * artifacts; merging them would be god-class drift. Auth decisions stay in
 * the facade (it pre-resolves the definition id via
 * {@link #resolveLatestDefinitionId} for its access check); this service only
 * signals 404/400 for missing/invalid data.
 */
@Component
@RequiredArgsConstructor
public class ElementBindingService {

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final FormRepository formRepository;
    private final ElementArtifactBindingRepository bindingRepository;

    /**
     * Latest definition id for a key (404 if missing). The facade resolves this
     * FIRST so its access check runs before any binding data is read; the main
     * methods below resolve again internally, keeping their bodies verbatim.
     */
    public UUID resolveLatestDefinitionId(String key) {
        Integer maxPdVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        return processDefinitionRepository.findByKeyAndVersion(key, maxPdVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"))
            .getId();
    }

    public ElementBindingDTO createElementBinding(String key, CreateElementBindingDTO dto) {
        if (dto.getElementId() == null || dto.getElementId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "elementId is required");
        }
        if (dto.getArtifactKey() == null || dto.getArtifactKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "artifactKey is required");
        }

        // Resolve latest version of process definition
        Integer maxPdVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxPdVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        // Resolve latest version of artifact (pin to this version)
        FormEntity artifact = formRepository.findTopByFormKeyOrderByVersionDesc(dto.getArtifactKey())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found: " + dto.getArtifactKey()));

        // Upsert: delete existing binding for same PD + elementId
        bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), dto.getElementId())
            .ifPresent(bindingRepository::delete);

        ElementArtifactBindingEntity binding = new ElementArtifactBindingEntity();
        binding.setId(UUID.randomUUID());
        binding.setProcessDefinitionId(pd.getId());
        binding.setProcessDefinitionVersion(pd.getVersion());
        binding.setElementId(dto.getElementId());
        binding.setArtifactKey(dto.getArtifactKey());
            binding.setArtifactVersion(artifact.getVersion());
            binding.setCreatedAt(Instant.now());
            bindingRepository.save(binding);

        ElementBindingDTO result = new ElementBindingDTO();
        result.setId(binding.getId());
        result.setElementId(binding.getElementId());
        result.setArtifactKey(binding.getArtifactKey());
        result.setArtifactVersion(binding.getArtifactVersion());
        result.setProcessDefinitionId(binding.getProcessDefinitionId());
        result.setProcessDefinitionVersion(binding.getProcessDefinitionVersion());
        return result;
    }

    public List<ElementBindingDTO> listElementBindings(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        return bindingRepository.findByProcessDefinitionId(pd.getId()).stream()
            .map(b -> {
                ElementBindingDTO dto = new ElementBindingDTO();
                dto.setId(b.getId());
                dto.setElementId(b.getElementId());
                dto.setArtifactKey(b.getArtifactKey());
                dto.setArtifactVersion(b.getArtifactVersion());
                dto.setProcessDefinitionId(b.getProcessDefinitionId());
                dto.setProcessDefinitionVersion(b.getProcessDefinitionVersion());
                return dto;
            })
            .toList();
    }

    public void deleteElementBinding(String key, String elementId) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        bindingRepository.deleteByProcessDefinitionIdAndElementId(pd.getId(), elementId);
    }
}
