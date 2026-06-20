package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.dmn.xml.DmnDecisionModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnDecisionTableModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnDefinitionsModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnRuleModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnTextModel;
import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DmnDefinitionRepository;
import com.zorrodev.bpm.engine.service.DmnService;
import jakarta.xml.bind.JAXB;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small DMN decision-table engine built directly on the project's FEEL engine (the same feel-scala line
 * Camunda 8 uses) — no separate DMN engine dependency. Supports single/multi-column decision tables with
 * the UNIQUE/FIRST/ANY hit policies; input entries are FEEL unary tests, input/output expressions are FEEL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DmnServiceImpl implements DmnService {

    private final FeelEngineApi feelEngineApi;
    private final DmnDefinitionRepository dmnDefinitionRepository;

    @Override
    public void deploy(String dmnXml) {
        DmnDefinitionsModel model = JAXB.unmarshal(new StringReader(dmnXml), DmnDefinitionsModel.class);
        if (model.getDecisions() == null || model.getDecisions().isEmpty()) {
            throw new EngineException("DMN resource has no decisions");
        }
        for (DmnDecisionModel decision : model.getDecisions()) {
            DmnDefinitionEntity entity = new DmnDefinitionEntity();
            entity.setDecisionId(decision.getId());
            entity.setDmn(dmnXml);
            entity.setCreatedAt(Instant.now());
            dmnDefinitionRepository.save(entity);
            log.info("Deployed DMN decision '{}'", decision.getId());
        }
    }

    @Override
    public Object evaluate(String decisionId, List<ProcessVariable> variables) {
        DmnDefinitionEntity entity = dmnDefinitionRepository.findById(decisionId)
            .orElseThrow(() -> new EngineException("No deployed DMN decision '" + decisionId + "'"));
        DmnDefinitionsModel model = JAXB.unmarshal(new StringReader(entity.getDmn()), DmnDefinitionsModel.class);
        DmnDecisionModel decision = model.getDecisions().stream()
            .filter(d -> decisionId.equals(d.getId()))
            .findFirst()
            .orElseThrow(() -> new EngineException("DMN resource has no decision '" + decisionId + "'"));
        DmnDecisionTableModel table = decision.getDecisionTable();
        if (table == null) {
            throw new EngineException("DMN decision '" + decisionId + "' has no decision table");
        }

        Map<String, Object> vars = toVariableMap(variables);
        int inputCount = table.getInputs() == null ? 0 : table.getInputs().size();

        // evaluate each input expression once
        List<Object> inputValues = new ArrayList<>();
        for (int i = 0; i < inputCount; i++) {
            String expr = text(table.getInputs().get(i).getInputExpression());
            inputValues.add(expr == null || expr.isBlank() ? null : evalExpression(expr, vars));
        }

        String hitPolicy = table.getHitPolicy() == null ? "UNIQUE" : table.getHitPolicy().toUpperCase();
        DmnRuleModel matched = null;
        for (DmnRuleModel rule : table.getRules()) {
            if (ruleMatches(rule, inputValues, vars)) {
                matched = rule;
                // UNIQUE/FIRST/ANY all return a single result — the first matching rule is sufficient here
                break;
            }
        }
        if (matched == null) {
            log.info("DMN decision '{}' matched no rule", decisionId);
            return null;
        }

        List<DmnTextModel> outputEntries = matched.getOutputEntries();
        if (table.getOutputs() != null && table.getOutputs().size() == 1) {
            Object result = outputValue(outputEntries.get(0), vars);
            log.info("DMN decision '{}' ({}) evaluated to {}", decisionId, hitPolicy, result);
            return result;
        }
        // multiple outputs: return a name -> value map
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < outputEntries.size(); i++) {
            String name = table.getOutputs().get(i).getName();
            result.put(name != null ? name : "output" + i, outputValue(outputEntries.get(i), vars));
        }
        log.info("DMN decision '{}' ({}) evaluated to {}", decisionId, hitPolicy, result);
        return result;
    }

    private boolean ruleMatches(DmnRuleModel rule, List<Object> inputValues, Map<String, Object> vars) {
        for (int i = 0; i < inputValues.size(); i++) {
            String entry = rule.getInputEntries() != null && i < rule.getInputEntries().size()
                ? text(rule.getInputEntries().get(i)) : null;
            if (entry == null || entry.isBlank() || entry.equals("-")) {
                continue; // empty cell matches any input
            }
            if (!evalUnaryTest(entry, inputValues.get(i), vars)) {
                return false;
            }
        }
        return true;
    }

    private Object outputValue(DmnTextModel entry, Map<String, Object> vars) {
        String expr = text(entry);
        return expr == null || expr.isBlank() ? null : evalExpression(expr, vars);
    }

    private Object evalExpression(String expression, Map<String, Object> vars) {
        EvaluationResult result = feelEngineApi.evaluateExpression(expression, vars);
        if (!result.isSuccess()) {
            throw new EngineException("DMN FEEL expression failed: '" + expression + "' -> " + result.failure());
        }
        return result.result();
    }

    private boolean evalUnaryTest(String test, Object input, Map<String, Object> vars) {
        EvaluationResult result = feelEngineApi.evaluateUnaryTests(test, input, vars);
        if (!result.isSuccess()) {
            throw new EngineException("DMN FEEL unary test failed: '" + test + "' -> " + result.failure());
        }
        return Boolean.TRUE.equals(result.result());
    }

    private String text(DmnTextModel model) {
        return model == null ? null : model.getText();
    }

    private Map<String, Object> toVariableMap(List<ProcessVariable> variables) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (variables == null) {
            return map;
        }
        for (ProcessVariable variable : variables) {
            ProcessVariableType type = variable.getType();
            if (type == ProcessVariableType.LONG) {
                map.put(variable.getName(), Long.valueOf(variable.getValue()));
            } else if (type == ProcessVariableType.BOOLEAN) {
                map.put(variable.getName(), Boolean.valueOf(variable.getValue()));
            } else {
                map.put(variable.getName(), variable.getValue());
            }
        }
        return map;
    }
}
