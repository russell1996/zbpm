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
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;

@RestController
@RequiredArgsConstructor
public class FormResource implements FormContract {

    private final ElementBindingOperations elementBindingOperations;
    private final HttpServletResponse httpResponse;
    private final TaskFormOperations taskFormOperations;
    private final FormOperations formOperations;
    private final SchemaMapOperations schemaMapOperations;

    @Override
    public List<FormDTO> listForms() {
        return formOperations.listForms();
    }

    @Override
    /**
     * WO-API-1 (API-1): create → 201 + Location. Статус — здесь (ресурс владеет
     * HTTP-семантикой); Location — по ключу формы.
     */
    @ResponseStatus(HttpStatus.CREATED)
    public FormDTO deployForm(@Valid @RequestBody DeployFormDTO dto) {
        FormDTO result = formOperations.deployForm(dto);
        if (httpResponse != null) {
            httpResponse.setHeader("Location", "/forms/" + result.getKey());
        }
        return result;
    }

    @Override
    public FormDTO getForm(String key) {
        return formOperations.getForm(key);
    }

    // --- WO-FORM-2: form resolve endpoints ---

    @Override
    public TaskFormDTO getUserTaskForm(UUID id) {
        return taskFormOperations.getUserTaskForm(id);
    }

    @Override
    public TaskFormDTO getStartForm(String key) {
        return taskFormOperations.getStartForm(key);
    }

    @Override
    public ElementBindingDTO createElementBinding(String key, CreateElementBindingDTO dto) {
        return elementBindingOperations.createElementBinding(key, dto);
    }

    @Override
    public List<ElementBindingDTO> listElementBindings(String key) {
        return elementBindingOperations.listElementBindings(key);
    }

    @Override
    public void deleteElementBinding(String key, String elementId) {
        elementBindingOperations.deleteElementBinding(key, elementId);
    }

    // --- WO-VM-9a: schema-map + save ---

    @Override
    public SchemaMapDTO getSchemaMap(String key) {
        return schemaMapOperations.getSchemaMap(key);
    }

    @Override
    public SchemaMapElementDTO saveElementSchema(String key, String elementId, SaveElementSchemaDTO dto) {
        return schemaMapOperations.saveElementSchema(key, elementId, dto);
    }
}
