package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.BpmnProcessStructure;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

public interface ProcessDefinitionContract {

    @PostExchange(value = "/processes/{key}/archive", accept = MediaType.APPLICATION_JSON_VALUE)
    void archiveProcess(@PathVariable("key") String key);

    @PostExchange(value = "/processes/{key}/unarchive", accept = MediaType.APPLICATION_JSON_VALUE)
    void unarchiveProcess(@PathVariable("key") String key);

    @PostExchange(value = "/process-definitions", accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
    ProcessDefinition addProcessDefinition(@Valid @RequestBody AddProcessDefinitionDTO dto);

    /**
     * WO-ACL-4 (ADR-8 п.3): new version of an EXISTING process — authorized by the target
     * definition {@code id} from the path (DEPLOY action), not by global role. The key inside
     * the uploaded XML must match the target process's key (it cannot be changed).
     */
    @PostExchange(value = "/process-definitions/{id}/versions", accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
    ProcessDefinition addProcessDefinitionVersion(@PathVariable("id") UUID id, @Valid @RequestBody AddProcessDefinitionDTO dto);

    @GetExchange(url = "/process-definitions", accept = MediaType.APPLICATION_JSON_VALUE)
    PagedDataDTO<ProcessDefinition> getProcessDefinitions(ProcessDefinitionsQueryParameters parameters);

    @GetExchange(url = "/process-definitions/{id}", accept = MediaType.APPLICATION_JSON_VALUE)
    ProcessDefinition getProcessDefinitionById(@PathVariable UUID id);

    @GetExchange(url = "/process-definitions/{id}/xml", accept = MediaType.APPLICATION_JSON_VALUE)
    String getProcessDefinitionXml(@PathVariable("id") UUID id);

    @GetExchange(url = "/process-definitions/{id}/structure", accept = MediaType.APPLICATION_JSON_VALUE)
    BpmnProcessStructure getProcessDefinitionStructure(@PathVariable("id") UUID id);
}
