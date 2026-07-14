package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

public interface FormContract {

    @GetMapping("/forms")
    List<FormDTO> listForms();

    @PostMapping("/forms")
    FormDTO deployForm(@RequestBody DeployFormDTO dto);

    @GetMapping("/forms/{key}")
    FormDTO getForm(@PathVariable String key);

    @GetMapping("/user-tasks/{id}/form")
    TaskFormDTO getUserTaskForm(@PathVariable UUID id);

    @GetMapping("/process-definitions/{key}/start-form")
    TaskFormDTO getStartForm(@PathVariable String key);
}
