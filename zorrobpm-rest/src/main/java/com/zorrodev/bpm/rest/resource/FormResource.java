package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.FormContract;
import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.ElementBindingDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapElementDTO;
import com.zorrodev.bpm.contract.dto.SaveElementSchemaDTO;
import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.FormResolver;
import com.zorrodev.bpm.engine.service.JsonSchemaValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
public class FormResource implements FormContract {

    private final FormRepository formRepository;
    private final UserTaskRepository userTaskRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ElementArtifactBindingRepository bindingRepository;
    private final DBService dbService;
    private final BpmnParseService bpmnParseService;
    private final BpmnService bpmnService;
    private final FormResolver formResolver;
    private final JsonSchemaValidator jsonSchemaValidator;
    private final HttpServletRequest request;
    private final ObjectMapper objectMapper;
    private final EventAuthzResolver eventAuthzResolver;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessRepository processRepository;

    /**
     * WO-ACL-1: form artifacts (list/get/start-form/bindings/schema-map) are DEFINITION
     * reads — any authenticated user sees them (ADR-8 §1); ServicePrincipal stays
     * grant-scoped.
     */
    private Collection<UUID> resolveAllowedPdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of();
        }
        return eventAuthzResolver.visibleDefinitionIds(principal, null);
    }

    /**
     * WO-ACL-1: the task form of a concrete process instance is RUNTIME data — it is
     * resolved per instance and prefilled with the instance's variables. Only process
     * members (or SUPER_ADMIN / granted ServicePrincipal) may read it.
     */
    private Collection<UUID> resolveRuntimePdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of();
        }
        return eventAuthzResolver.readableRuntimePdIds(principal, null);
    }

    private void requirePdAccess(UUID pdId) {
        Collection<UUID> allowed = resolveAllowedPdIds();
        if (allowed != null && !allowed.contains(pdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    private void requireRuntimePdAccess(UUID pdId) {
        Collection<UUID> allowed = resolveRuntimePdIds();
        if (allowed != null && !allowed.contains(pdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    @Override
    public List<FormDTO> listForms() {
        Collection<UUID> allowedPdIds = resolveAllowedPdIds();

        // Get all form keys that are bound to allowed process definitions
        Set<String> allowedFormKeys;
        if (allowedPdIds == null) {
            // SuperAdmin / full grant — see all forms
            allowedFormKeys = null; // null = no filter
        } else if (allowedPdIds.isEmpty()) {
            // No grants — only show unbound forms (not tied to any process)
            allowedFormKeys = getUnboundFormKeys();
        } else {
            allowedFormKeys = getFormKeysBoundToPds(allowedPdIds);
            // Also include unbound forms
            allowedFormKeys.addAll(getUnboundFormKeys());
        }

        return formRepository.findLatestVersions().stream()
            .filter(entity -> allowedFormKeys == null || allowedFormKeys.contains(entity.getFormKey()))
            .map(entity -> {
                FormDTO dto = new FormDTO();
                dto.setKey(entity.getFormKey());
                dto.setVersion(entity.getVersion());
                dto.setKind(entity.getKind() != null ? entity.getKind().name() : null);
                dto.setSchema(entity.getSchemaJson());
                return dto;
            }).toList();
    }

    @Override
    @Transactional
    public FormDTO deployForm(@RequestBody DeployFormDTO dto) {
        // WO-FORM-1: only SUPER_ADMIN can deploy forms
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deploy requires SUPER_ADMIN");
        }

        if (dto.getKey() == null || dto.getKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form key is required");
        }
        if (dto.getKind() == null || dto.getKind().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind is required");
        }
        FormArtifactKind kind;
        try {
            kind = FormArtifactKind.valueOf(dto.getKind());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid kind: " + dto.getKind() + ". Must be FORM_JS or VARIABLE_SCHEMA");
        }
        if (dto.getSchema() == null || dto.getSchema().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form schema is required");
        }

        // Validate JSON
        try {
            objectMapper.readValue(dto.getSchema(), Object.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }

        // WO-VM-5: validate JSON Schema structure for VARIABLE_SCHEMA
        if (kind == FormArtifactKind.VARIABLE_SCHEMA) {
            java.util.Set<String> errors = jsonSchemaValidator.validateSchema(dto.getSchema());
            if (!errors.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid JSON Schema: " + String.join("; ", errors));
            }
        }

        // Versioning: version = max + 1
        int maxVersion = formRepository.findMaxVersionByFormKey(dto.getKey());
        FormEntity entity = new FormEntity();
        entity.setId(UUID.randomUUID());
        entity.setFormKey(dto.getKey());
        entity.setVersion(maxVersion + 1);
        entity.setKind(kind);
        entity.setSchemaJson(dto.getSchema());
        entity.setCreatedAt(Instant.now());
        formRepository.save(entity);

        FormDTO result = new FormDTO();
        result.setKey(entity.getFormKey());
        result.setVersion(entity.getVersion());
        result.setKind(entity.getKind().name());
        result.setSchema(entity.getSchemaJson());
        return result;
    }

    @Override
    public FormDTO getForm(String key) {
        FormEntity entity = formRepository.findTopByFormKeyOrderByVersionDesc(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form not found"));

        // Authz: 404 if form belongs to an inaccessible process (G-L: deny by default)
        checkFormAccess(key);

        FormDTO dto = new FormDTO();
        dto.setKey(entity.getFormKey());
        dto.setVersion(entity.getVersion());
        dto.setKind(entity.getKind() != null ? entity.getKind().name() : null);
        dto.setSchema(entity.getSchemaJson());
        return dto;
    }

    // --- WO-FORM-2: form resolve endpoints ---

    @Override
    public TaskFormDTO getUserTaskForm(UUID id) {
        UserTaskEntity task = userTaskRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User task not found"));
        ProcessInstanceEntity pi = processInstanceRepository.findById(task.getProcessInstanceId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        // WO-ACL-1: task form of a concrete instance = runtime read (variables prefill)
        requireRuntimePdAccess(pi.getProcessDefinitionId());
        return resolveForm(task.getFormKey(), task.getProcessInstanceId());
    }

    @Override
    public TaskFormDTO getStartForm(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        requirePdAccess(pd.getId());

        // ADR-6 §D7: try element-artifact binding first (per elementId)
        List<ElementArtifactBindingEntity> bindings = bindingRepository.findByProcessDefinitionId(pd.getId());
        if (!bindings.isEmpty()) {
            // For now, return the first binding's artifact (start event binding)
            ElementArtifactBindingEntity binding = bindings.get(0);
            return resolveByBinding(binding);
        }

        // Fallback to scalar startFormKey (back-compat)
        return resolveStartForm(pd.getStartFormKey());
    }

    @Override
    @Transactional
    public ElementBindingDTO createElementBinding(String key, CreateElementBindingDTO dto) {
        // SUPER_ADMIN only
        Principal principal = getPrincipal();
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
        requirePdAccess(pd.getId());

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

    @Override
    @Transactional
    public void deleteElementBinding(String key, String elementId) {
        Principal principal = getPrincipal();
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

    // --- WO-VM-9a: schema-map + save ---

    @Override
    public SchemaMapDTO getSchemaMap(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        // WO-SEC-59 #7: authz first — a principal without access to the process must not read its form schema.
        requirePdAccess(pd.getId());

        // Parse BPMN to get elements
        var model = bpmnService.getProcessDefinitionModelById(pd.getId());

        // Get bindings for this PD
        List<ElementArtifactBindingEntity> bindings = bindingRepository.findByProcessDefinitionId(pd.getId());
        Map<String, ElementArtifactBindingEntity> bindingByElement = bindings.stream()
            .collect(java.util.stream.Collectors.toMap(ElementArtifactBindingEntity::getElementId, b -> b, (a, b) -> a));

        // WO-VM-9a fix: shared = GLOBALLY — count ALL bindings across ALL PDs + user-task externalReferences
        // A key is shared if >1 element (across all processes) uses it
        Map<String, Long> globalArtifactUsage = new java.util.HashMap<>();
        // Count from all bindings (all PDs)
        bindingRepository.findAll().forEach(b ->
            globalArtifactUsage.merge(b.getArtifactKey(), 1L, Long::sum));
        // Count from user-task externalReferences across all PDs
        for (ProcessDefinitionEntity allPd : processDefinitionRepository.findAll()) {
            try {
                var allModel = bpmnService.getProcessDefinitionModelById(allPd.getId());
                allModel.getElements().stream()
                    .filter(e -> e.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK)
                    .forEach(e -> {
                        if (e.getExtensions() != null && e.getExtensions().getUserTaskExtension() != null
                            && e.getExtensions().getUserTaskExtension().getFormKey() != null) {
                            globalArtifactUsage.merge(e.getExtensions().getUserTaskExtension().getFormKey(), 1L, Long::sum);
                        }
                    });
            } catch (Exception e) {
                log.warn("Failed to parse process definition {} for schema-map usage count: {}", allPd.getId(), e.getMessage());
            }
        }

        List<SchemaMapElementDTO> elements = model.getElements().stream()
            .filter(e -> e.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.START_EVENT
                || e.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK)
            .map(e -> {
                SchemaMapElementDTO dto = new SchemaMapElementDTO();
                dto.setElementId(e.getId());
                dto.setName(e.getName());
                dto.setType(e.getType().name());

                String artifactKey = null;
                boolean hasExternalReference = false;

                if (e.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK) {
                    // User task: formKey from extensions (may be externalReference or formKey)
                    if (e.getExtensions() != null && e.getExtensions().getUserTaskExtension() != null) {
                        artifactKey = e.getExtensions().getUserTaskExtension().getFormKey();
                        hasExternalReference = e.getExtensions().getUserTaskExtension().getExternalReference() != null;
                    }
                } else {
                    // Start event: check binding
                    ElementArtifactBindingEntity binding = bindingByElement.get(e.getId());
                    if (binding != null) {
                        artifactKey = binding.getArtifactKey();
                    }
                }

                dto.setArtifactKey(artifactKey);

                if (artifactKey != null) {
                    // Resolve artifact kind and version
                    formRepository.findTopByFormKeyOrderByVersionDesc(artifactKey).ifPresent(form -> {
                        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
                        dto.setArtifactVersion(form.getVersion());
                    });
                    dto.setShared(globalArtifactUsage.getOrDefault(artifactKey, 0L) > 1);
                }

                dto.setHasExternalReference(hasExternalReference);
                return dto;
            })
            .toList();

        SchemaMapDTO result = new SchemaMapDTO();
        result.setProcessDefinitionKey(pd.getKey());
        result.setVersion(pd.getVersion());
        result.setElements(elements);
        return result;
    }

    @Override
    @Transactional
    public SchemaMapElementDTO saveElementSchema(String key, String elementId, SaveElementSchemaDTO dto) {
        // SUPER_ADMIN only
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can save element schemas");
        }

        // Validate kind
        if (dto.getKind() == null || dto.getKind().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind is required");
        }
        FormArtifactKind kind;
        try {
            kind = FormArtifactKind.valueOf(dto.getKind());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid kind: " + dto.getKind());
        }

        // Validate schema
        if (dto.getSchema() == null || dto.getSchema().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schema is required");
        }
        try {
            objectMapper.readValue(dto.getSchema(), Object.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }
        if (kind == FormArtifactKind.VARIABLE_SCHEMA) {
            java.util.Set<String> errors = jsonSchemaValidator.validateSchema(dto.getSchema());
            if (!errors.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid JSON Schema: " + String.join("; ", errors));
            }
        }

        // Resolve PD
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        // Parse BPMN to find element and determine artifactKey
        var model = bpmnService.getProcessDefinitionModelById(pd.getId());
        var element = model.getElements().stream()
            .filter(e -> e.getId().equals(elementId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Element not found: " + elementId));

        String artifactKey;
        if (element.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK) {
            // User task: must have externalReference
            if (element.getExtensions() == null
                || element.getExtensions().getUserTaskExtension() == null
                || element.getExtensions().getUserTaskExtension().getExternalReference() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "User task has no External Form Reference. Set it in Camunda Modeler first.");
            }
            artifactKey = element.getExtensions().getUserTaskExtension().getExternalReference();
        } else {
            // Start event: auto-generate key
            artifactKey = key + ":" + elementId;
        }

        // Create new artifact version
        int maxArtifactVersion = formRepository.findMaxVersionByFormKey(artifactKey);
        FormEntity artifact = new FormEntity();
        artifact.setId(UUID.randomUUID());
        artifact.setFormKey(artifactKey);
        artifact.setVersion(maxArtifactVersion + 1);
        artifact.setKind(kind);
        artifact.setSchemaJson(dto.getSchema());
        artifact.setCreatedAt(Instant.now());
        formRepository.save(artifact);

        // Start event: upsert binding with pinning
        if (element.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.START_EVENT) {
            bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), elementId)
                .ifPresent(bindingRepository::delete);

            ElementArtifactBindingEntity binding = new ElementArtifactBindingEntity();
            binding.setId(UUID.randomUUID());
            binding.setProcessDefinitionId(pd.getId());
            binding.setProcessDefinitionVersion(pd.getVersion());
            binding.setElementId(elementId);
            binding.setArtifactKey(artifactKey);
            binding.setArtifactVersion(artifact.getVersion());
            binding.setCreatedAt(Instant.now());
            bindingRepository.save(binding);
        }

        // Return updated element status
        SchemaMapElementDTO result = new SchemaMapElementDTO();
        result.setElementId(elementId);
        result.setName(element.getName());
        result.setType(element.getType().name());
        result.setArtifactKey(artifactKey);
        result.setKind(kind.name());
        result.setArtifactVersion(artifact.getVersion());
        result.setHasExternalReference(
            element.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK
                && element.getExtensions() != null
                && element.getExtensions().getUserTaskExtension() != null
                && element.getExtensions().getUserTaskExtension().getExternalReference() != null);
        return result;
    }

    // --- WO-FORM-2: resolve logic ---

    private TaskFormDTO resolveForm(String formKey, UUID processInstanceId) {
        return formResolver.resolveTaskForm(formKey, prefillData(processInstanceId));
    }

    private TaskFormDTO resolveStartForm(String startFormKey) {
        return formResolver.resolveTaskForm(startFormKey, null);
    }

    private TaskFormDTO resolveByBinding(ElementArtifactBindingEntity binding) {
        // ADR-6 §D8: pin to artifact_version from binding
        FormEntity form = formRepository.findByFormKeyAndVersion(binding.getArtifactKey(), binding.getArtifactVersion())
            .orElse(null);
        if (form == null) {
            // Fallback: try latest version
            return resolveStartForm(binding.getArtifactKey());
        }
        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
        dto.setSchema(form.getSchemaJson());
        return dto;
    }

    private Map<String, String> prefillData(UUID processInstanceId) {
        Map<String, String> data = new LinkedHashMap<>();
        if (processInstanceId == null) return data;
        java.util.List<ProcessVariable> vars = dbService.getVariables(processInstanceId);
        for (ProcessVariable v : vars) {
            if (v.getName() != null && v.getValue() != null) {
                data.put(v.getName(), v.getValue());
            }
        }
        return data;
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    // --- WO-SEC-47: authz helpers ---

    /**
     * Returns form keys that are NOT bound to any process definition (global forms).
     * These are visible to all authenticated principals regardless of process-level grants.
     */
    private Set<String> getUnboundFormKeys() {
        // All form keys that appear in at least one binding
        Set<String> boundKeys = bindingRepository.findAll().stream()
            .map(ElementArtifactBindingEntity::getArtifactKey)
            .collect(Collectors.toSet());
        // All form keys from the form repository
        Set<String> allKeys = formRepository.findLatestVersions().stream()
            .map(FormEntity::getFormKey)
            .collect(Collectors.toSet());
        // Unbound = all keys minus bound keys
        Set<String> unbound = new java.util.HashSet<>(allKeys);
        unbound.removeAll(boundKeys);
        return unbound;
    }

    /**
     * Returns form keys that are bound to at least one of the given process definition IDs.
     */
    private Set<String> getFormKeysBoundToPds(Collection<UUID> pdIds) {
        return bindingRepository.findByProcessDefinitionIdIn(pdIds).stream()
            .map(ElementArtifactBindingEntity::getArtifactKey)
            .collect(Collectors.toSet());
    }

    /**
     * Checks that the current principal has access to the process definitions
     * associated with the given form key. Throws 404 if denied (G-L: deny by default).
     * Unbound forms (not tied to any process) are accessible to all authenticated principals.
     */
    private void checkFormAccess(String formKey) {
        Collection<UUID> allowedPdIds = resolveAllowedPdIds();
        if (allowedPdIds == null) {
            return; // superAdmin / full grant — see all
        }
        // Find process definitions this form is bound to
        List<UUID> boundPdIds = bindingRepository.findByArtifactKey(formKey).stream()
            .map(ElementArtifactBindingEntity::getProcessDefinitionId)
            .distinct()
            .toList();
        if (boundPdIds.isEmpty()) {
            return; // unbound form — accessible to all authenticated principals
        }
        boolean hasAccess = boundPdIds.stream().anyMatch(allowedPdIds::contains);
        if (!hasAccess) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }
}
