package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/forms")
public interface FormContract {

    @PostMapping
    FormDTO deployForm(@RequestBody DeployFormDTO dto);

    @GetMapping("/{key}")
    FormDTO getForm(@PathVariable String key);
}
