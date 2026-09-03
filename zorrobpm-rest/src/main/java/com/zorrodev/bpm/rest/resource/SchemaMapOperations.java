package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.SaveElementSchemaDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapElementDTO;

public interface SchemaMapOperations {

    SchemaMapDTO getSchemaMap(String key);

    SchemaMapElementDTO saveElementSchema(String key, String elementId, SaveElementSchemaDTO dto);
}
