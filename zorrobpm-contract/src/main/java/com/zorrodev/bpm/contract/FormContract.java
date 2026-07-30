package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.ElementBindingDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapElementDTO;
import com.zorrodev.bpm.contract.dto.SaveElementSchemaDTO;
import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

public interface FormContract {

    @GetMapping("/forms")
    List<FormDTO> listForms();

    @PostMapping("/forms")
    FormDTO deployForm(@Valid @RequestBody DeployFormDTO dto);

    @GetMapping("/forms/{key}")
    FormDTO getForm(@PathVariable String key);

    @GetMapping("/user-tasks/{id}/form")
    TaskFormDTO getUserTaskForm(@PathVariable UUID id);

    @GetMapping("/process-definitions/{key}/start-form")
    TaskFormDTO getStartForm(@PathVariable String key);

    @PostMapping("/process-definitions/{key}/element-bindings")
    ElementBindingDTO createElementBinding(@PathVariable String key, @Valid @RequestBody CreateElementBindingDTO dto);

    @GetMapping("/process-definitions/{key}/element-bindings")
    List<ElementBindingDTO> listElementBindings(@PathVariable String key);

    @DeleteMapping("/process-definitions/{key}/element-bindings/{elementId}")
    void deleteElementBinding(@PathVariable String key, @PathVariable String elementId);

    @GetMapping("/process-definitions/{key}/schema-map")
    SchemaMapDTO getSchemaMap(@PathVariable String key);

    @PostMapping("/process-definitions/{key}/elements/{elementId}/schema")
    SchemaMapElementDTO saveElementSchema(@PathVariable String key, @PathVariable String elementId, @Valid @RequestBody SaveElementSchemaDTO dto);
}
