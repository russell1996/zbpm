package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.ElementBindingDTO;

import java.util.List;

public interface ElementBindingOperations {

    ElementBindingDTO createElementBinding(String key, CreateElementBindingDTO dto);

    List<ElementBindingDTO> listElementBindings(String key);

    void deleteElementBinding(String key, String elementId);
}
