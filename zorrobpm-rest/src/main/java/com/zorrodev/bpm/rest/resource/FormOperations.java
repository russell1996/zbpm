package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;

import java.util.List;

public interface FormOperations {

    List<FormDTO> listForms();

    FormDTO deployForm(DeployFormDTO dto);

    FormDTO getForm(String key);
}
