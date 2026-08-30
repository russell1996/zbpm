package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен Variables.
 */
public interface VariableDbOperations {

    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId);

    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId, UUID scopeId);

    void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables);

    void setVariables(@NonNull UUID processInstanceId, UUID scopeId, List<ProcessVariable> variables);

    void deleteVariables(@NonNull UUID processInstanceId, UUID scopeId);
}
