package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.GenerateSchemaDTO;
import com.zorrodev.bpm.contract.dto.GeneratedSchemaDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

public interface VariableSchemaContract {

    @PostMapping("/variable-schemas/generate")
    GeneratedSchemaDTO generateSchema(@Valid @RequestBody GenerateSchemaDTO dto);
}
