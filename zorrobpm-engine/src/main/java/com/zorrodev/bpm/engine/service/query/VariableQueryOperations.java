package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.VariableScopeGroup;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VariableQueryOperations {

    PagedDataDTO<ProcessVariable> findVariables(VariableQuery query, Collection<UUID> allowedPdIds);

    /** WO-C8-51 (часть A): все переменные инстанса, сгруппированные по скоупам (root первым). */
    List<VariableScopeGroup> variablesByScope(UUID processInstanceId);
}
