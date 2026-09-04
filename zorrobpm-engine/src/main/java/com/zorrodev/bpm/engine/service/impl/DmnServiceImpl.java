package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.DmnDecision;
import com.zorrodev.bpm.contract.model.DmnInput;
import com.zorrodev.bpm.contract.model.DmnOutput;
import com.zorrodev.bpm.contract.model.DmnRule;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.dmn.xml.DmnDecisionModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnDecisionTableModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnDefinitionsModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnInputModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnOutputModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnRuleModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnTextModel;
import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DmnDefinitionRepository;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.xml.SecureXmlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private final ObjectMapper objectMapper;

    @Override
    public void deploy(String dmnXml) {
        deploy(dmnXml, null);
    }

    @Override
    public void deploy(String dmnXml, UUID processDefinitionId) {
        DmnDefinitionsModel model = SecureXmlParser.unmarshal(dmnXml, DmnDefinitionsModel.class);
        if (model.getDecisions() == null || model.getDecisions().isEmpty()) {
            throw new EngineException("DMN resource has no decisions");
        }
        for (DmnDecisionModel decision : model.getDecisions()) {
            int version = dmnDefinitionRepository.findFirstByDecisionIdOrderByVersionDesc(decision.getId())
                .map(e -> e.getVersion() + 1)
                .orElse(1);
            DmnDefinitionEntity entity = new DmnDefinitionEntity();
            entity.setId(java.util.UUID.randomUUID());
            entity.setDecisionId(decision.getId());
            entity.setVersion(version);
            entity.setDmn(dmnXml);
            entity.setCreatedAt(Instant.now());
            entity.setProcessDefinitionId(processDefinitionId);
            dmnDefinitionRepository.save(entity);
            log.info("Deployed DMN decision '{}' version {}", decision.getId(), version);
        }
    }

    @Override
    public Object evaluate(String decisionId, List<ProcessVariable> variables) {
        DmnDefinitionEntity entity = dmnDefinitionRepository.findFirstByDecisionIdOrderByVersionDesc(decisionId)
            .orElseThrow(() -> new EngineException("No deployed DMN decision '" + decisionId + "'"));
        DmnDefinitionsModel model = SecureXmlParser.unmarshal(entity.getDmn(), DmnDefinitionsModel.class);
        DmnDecisionModel decision = model.getDecisions().stream()
            .filter(d -> decisionId.equals(d.getId()))
            .findFirst()
            .orElseThrow(() -> new EngineException("DMN resource has no decision '" + decisionId + "'"));
        DmnDecisionTableModel table = decision.getDecisionTable();
        Map<String, Object> vars = toVariableMap(variables);
        if (table == null) {
            // WO-C8-6: a decision without a table may carry a literal FEEL expression instead.
            DmnTextModel literalExpression = decision.getLiteralExpression();
            if (literalExpression != null) {
                String expr = text(literalExpression);
                if (expr == null || expr.isBlank()) {
                    throw new EngineException("DMN decision '" + decisionId + "' literal expression is empty");
                }
                Object result = evalExpression(expr, vars);
                log.info("DMN decision '{}' (literal expression) evaluated to {}", decisionId, result);
                return result;
            }
            throw new EngineException("DMN decision '" + decisionId + "' has no decision table");
        }

        int inputCount = table.getInputs() == null ? 0 : table.getInputs().size();

        // evaluate each input expression once
        List<Object> inputValues = new ArrayList<>();
        for (int i = 0; i < inputCount; i++) {
            String expr = text(table.getInputs().get(i).getInputExpression());
            inputValues.add(expr == null || expr.isBlank() ? null : evalExpression(expr, vars));
        }

        String hitPolicy = table.getHitPolicy() == null ? "UNIQUE" : table.getHitPolicy().toUpperCase();
        String aggregation = table.getAggregation() == null ? null : table.getAggregation().toUpperCase();

        // Collect all matching rules
        List<DmnRuleModel> matchedRules = new ArrayList<>();
        for (DmnRuleModel rule : table.getRules()) {
            if (ruleMatches(rule, inputValues, vars)) {
                matchedRules.add(rule);
            }
        }

        if (matchedRules.isEmpty()) {
            log.info("DMN decision '{}' matched no rule", decisionId);
            return null;
        }

        return switch (hitPolicy) {
            case "COLLECT" -> evaluateCollect(matchedRules, table, vars, decisionId, aggregation);
            case "RULE ORDER" -> evaluateRuleOrder(matchedRules, table, vars, decisionId);
            case "OUTPUT ORDER" -> evaluateOutputOrder(matchedRules, table, vars, decisionId);
            case "PRIORITY" -> evaluatePriority(matchedRules, table, vars, decisionId);
            case "UNIQUE" -> {
                if (matchedRules.size() > 1) {
                    throw new EngineException("DMN UNIQUE hit policy: expected exactly 1 matching rule, found " + matchedRules.size());
                }
                yield evaluateSingleRule(matchedRules.get(0), table, vars, decisionId);
            }
            default -> evaluateSingleRule(matchedRules.get(0), table, vars, decisionId); // FIRST, ANY
        };
    }

    private Object evaluateSingleRule(DmnRuleModel rule, DmnDecisionTableModel table, Map<String, Object> vars, String decisionId) {
        List<DmnTextModel> outputEntries = rule.getOutputEntries();
        if (table.getOutputs() != null && table.getOutputs().size() == 1) {
            Object result = outputValue(outputEntries.get(0), vars);
            log.info("DMN decision '{}' evaluated to {}", decisionId, result);
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < outputEntries.size(); i++) {
            String name = table.getOutputs().get(i).getName();
            result.put(name != null ? name : "output" + i, outputValue(outputEntries.get(i), vars));
        }
        log.info("DMN decision '{}' evaluated to {}", decisionId, result);
        return result;
    }

    private Object evaluateCollect(List<DmnRuleModel> rules, DmnDecisionTableModel table, Map<String, Object> vars, String decisionId, String aggregation) {
        List<Object> results = new ArrayList<>();
        for (DmnRuleModel rule : rules) {
            List<DmnTextModel> outputEntries = rule.getOutputEntries();
            if (table.getOutputs() != null && table.getOutputs().size() == 1) {
                results.add(outputValue(outputEntries.get(0), vars));
            } else {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < outputEntries.size(); i++) {
                    String name = table.getOutputs().get(i).getName();
                    row.put(name != null ? name : "output" + i, outputValue(outputEntries.get(i), vars));
                }
                results.add(row);
            }
        }

        if (aggregation != null) {
            return aggregate(results, aggregation, decisionId);
        }
        log.info("DMN decision '{}' (COLLECT) evaluated to {} results", decisionId, results.size());
        return results;
    }

    private Object aggregate(List<Object> results, String aggregation, String decisionId) {
        if (results.isEmpty()) return null;
        return switch (aggregation) {
            case "SUM" -> results.stream().mapToDouble(r -> ((Number) r).doubleValue()).sum();
            case "MIN" -> results.stream().mapToDouble(r -> ((Number) r).doubleValue()).min().orElse(0);
            case "MAX" -> results.stream().mapToDouble(r -> ((Number) r).doubleValue()).max().orElse(0);
            case "COUNT" -> (double) results.size();
            default -> results; // unknown aggregation → return list
        };
    }

    private Object evaluateRuleOrder(List<DmnRuleModel> rules, DmnDecisionTableModel table, Map<String, Object> vars, String decisionId) {
        List<Object> results = new ArrayList<>();
        for (DmnRuleModel rule : rules) {
            results.add(evaluateSingleRule(rule, table, vars, decisionId));
        }
        log.info("DMN decision '{}' (RULE ORDER) evaluated to {} results", decisionId, results.size());
        return results;
    }

    private Object evaluateOutputOrder(List<DmnRuleModel> rules, DmnDecisionTableModel table, Map<String, Object> vars, String decisionId) {
        // OUTPUT ORDER: results sorted by output values (ascending)
        List<Object> results = new ArrayList<>();
        for (DmnRuleModel rule : rules) {
            results.add(evaluateSingleRule(rule, table, vars, decisionId));
        }
        results.sort((a, b) -> {
            if (a instanceof Comparable && b instanceof Comparable) {
                return ((Comparable<Object>) a).compareTo(b);
            }
            return 0;
        });
        log.info("DMN decision '{}' (OUTPUT ORDER) evaluated to {} results", decisionId, results.size());
        return results;
    }

    private Object evaluatePriority(List<DmnRuleModel> rules, DmnDecisionTableModel table, Map<String, Object> vars, String decisionId) {
        // PRIORITY: return the result with the highest priority (first output, descending)
        DmnRuleModel bestRule = rules.get(0);
        Object bestValue = null;
        for (DmnRuleModel rule : rules) {
            Object value = outputValue(rule.getOutputEntries().get(0), vars);
            if (bestValue == null || (value instanceof Comparable && ((Comparable<Object>) value).compareTo(bestValue) > 0)) {
                bestValue = value;
                bestRule = rule;
            }
        }
        return evaluateSingleRule(bestRule, table, vars, decisionId);
    }

    @Override
    public List<DmnDecision> listDecisions() {
        return listDecisions(null);
    }

    @Override
    public List<DmnDecision> listDecisions(Collection<UUID> allowedPdIds) {
        // keep the latest version of each decisionId
        Map<String, DmnDefinitionEntity> latest = new LinkedHashMap<>();
        for (DmnDefinitionEntity e : dmnDefinitionRepository.findAll()) {
            if (!visible(e, allowedPdIds)) {
                continue;
            }
            DmnDefinitionEntity current = latest.get(e.getDecisionId());
            if (current == null || e.getVersion() > current.getVersion()) {
                latest.put(e.getDecisionId(), e);
            }
        }
        List<DmnDecision> result = new ArrayList<>();
        for (DmnDefinitionEntity e : latest.values()) {
            result.add(toDecisionDTO(e));
        }
        return result;
    }

    /**
     * WO-SEC-40: a decision is visible to a principal iff its scoped process definition id is
     * among the allowed ones. {@code null} allowed = see all (superAdmin / full grant).
     * Decisions with no process definition scope are only visible to "see all" principals —
     * a scoped principal cannot see unscoped decisions (DENY by default).
     */
    private boolean visible(DmnDefinitionEntity entity, Collection<UUID> allowedPdIds) {
        if (allowedPdIds == null) {
            return true;
        }
        return entity.getProcessDefinitionId() != null
            && allowedPdIds.contains(entity.getProcessDefinitionId());
    }

    @Override
    public Optional<UUID> findProcessDefinitionId(String decisionId) {
        return dmnDefinitionRepository.findLatestProcessDefinitionId(decisionId);
    }

    @Override
    public DmnDecision getDecision(String decisionId) {
        DmnDefinitionEntity entity = dmnDefinitionRepository.findFirstByDecisionIdOrderByVersionDesc(decisionId)
            .orElseThrow(() -> new EngineException("No deployed DMN decision '" + decisionId + "'"));
        return toDecisionDTO(entity);
    }

    private DmnDecision toDecisionDTO(DmnDefinitionEntity entity) {
        DmnDefinitionsModel model = SecureXmlParser.unmarshal(entity.getDmn(), DmnDefinitionsModel.class);
        DmnDecisionModel decision = model.getDecisions().stream()
            .filter(d -> entity.getDecisionId().equals(d.getId()))
            .findFirst()
            .orElseThrow(() -> new EngineException("DMN resource has no decision '" + entity.getDecisionId() + "'"));

        DmnDecision dto = new DmnDecision();
        dto.setId(decision.getId());
        dto.setName(decision.getName());
        dto.setVersion(entity.getVersion());
        dto.setCreatedAt(entity.getCreatedAt());

        List<DmnInput> inputs = new ArrayList<>();
        List<DmnOutput> outputs = new ArrayList<>();
        List<DmnRule> rules = new ArrayList<>();
        DmnDecisionTableModel table = decision.getDecisionTable();
        if (table != null) {
            dto.setHitPolicy(table.getHitPolicy() == null ? "UNIQUE" : table.getHitPolicy().toUpperCase());
            if (table.getInputs() != null) {
                for (DmnInputModel in : table.getInputs()) {
                    DmnInput i = new DmnInput();
                    i.setId(in.getId());
                    i.setLabel(in.getLabel());
                    i.setExpression(text(in.getInputExpression()));
                    inputs.add(i);
                }
            }
            if (table.getOutputs() != null) {
                for (DmnOutputModel out : table.getOutputs()) {
                    DmnOutput o = new DmnOutput();
                    o.setId(out.getId());
                    o.setLabel(out.getLabel());
                    o.setName(out.getName());
                    outputs.add(o);
                }
            }
            if (table.getRules() != null) {
                int idx = 0;
                for (DmnRuleModel r : table.getRules()) {
                    DmnRule rule = new DmnRule();
                    rule.setId("rule-" + (idx++));
                    rule.setInputEntries(textList(r.getInputEntries()));
                    rule.setOutputEntries(textList(r.getOutputEntries()));
                    rules.add(rule);
                }
            }
        }
        dto.setInputs(inputs);
        dto.setOutputs(outputs);
        dto.setRules(rules);
        return dto;
    }

    private List<String> textList(List<DmnTextModel> entries) {
        List<String> out = new ArrayList<>();
        if (entries != null) {
            for (DmnTextModel t : entries) out.add(text(t));
        }
        return out;
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
            } else if (type == ProcessVariableType.DOUBLE) {
                // FEEL numbers are BigDecimal — decimals must enter the decision table as numbers
                map.put(variable.getName(), new java.math.BigDecimal(variable.getValue()));
            } else if (type == ProcessVariableType.JSON) {
                // JSON object/list -> Java Map/List so FEEL can read nested properties and iterate
                map.put(variable.getName(), objectMapper.readValue(variable.getValue(), Object.class));
            } else {
                map.put(variable.getName(), variable.getValue());
            }
        }
        return map;
    }
}
