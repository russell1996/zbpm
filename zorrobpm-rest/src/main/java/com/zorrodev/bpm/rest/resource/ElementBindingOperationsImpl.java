package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.ElementBindingDTO;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-4c — ElementBinding domain slice. Byte-for-byte move of the 3 endpoint
 * bodies from {@code FormResource} (only {@code getPrincipal()}/{@code requirePdAccess}
 * re-pointed at {@link FormAccessSupport}); {@code @Transactional} moved with
 * {@code createElementBinding}/{@code deleteElementBinding}, {@code listElementBindings}
 * stays non-transactional as in the original.
 */
@Service
@RequiredArgsConstructor
public class ElementBindingOperationsImpl implements ElementBindingOperations {

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final FormRepository formRepository;
    private final ElementArtifactBindingRepository bindingRepository;
    private final FormAccessSupport formAccessSupport;

    @Transactional
    @Override
    public ElementBindingDTO createElementBinding(String key, CreateElementBindingDTO dto) {
        // SUPER_ADMIN only
        Principal principal = formAccessSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can create element bindings");
        }

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

    @Override
    public List<ElementBindingDTO> listElementBindings(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        // Authz: deny if principal lacks access to this process definition (G-L)
        formAccessSupport.requirePdAccess(pd.getId());

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

    @Transactional
    @Override
    public void deleteElementBinding(String key, String elementId) {
        Principal principal = formAccessSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can delete element bindings");
        }

        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        bindingRepository.deleteByProcessDefinitionIdAndElementId(pd.getId(), elementId);
    }
}
