package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.DmnDecision;
import com.zorrodev.bpm.contract.model.DmnInput;
import com.zorrodev.bpm.contract.model.DmnOutput;
import com.zorrodev.bpm.contract.model.DmnRule;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.dmn.xml.DmnDecisionModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnDecisionExtensionModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnDecisionTableModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnDefinitionsModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnInputModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnOutputModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnRuleModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnTextModel;
import com.zorrodev.bpm.engine.dmn.xml.DmnVersionTagModel;
import com.zorrodev.bpm.engine.dmn.xml.InformationRequirementModel;
import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DmnDefinitionRepository;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.xml.SecureXmlParser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
public class DmnServiceImpl implements DmnService {

    private final FeelEngineApi feelEngineApi;
    private final DmnDefinitionRepository dmnDefinitionRepository;
    private final ObjectMapper objectMapper;
    private final com.zorrodev.bpm.engine.service.AdvisoryDeployLock advisoryDeployLock;

    /**
     * WO-AUDIT-3 (P1): parsed DMN models by definition-row id — same shape as
     * {@code BpmnServiceImpl} cache. A definition version is immutable (new version =
     * new row id), so no invalidation is needed; deploys stamp the cache directly.
     */
    private final Cache<UUID, DmnDefinitionsModel> parsedModelCache;

    public DmnServiceImpl(
        FeelEngineApi feelEngineApi,
        DmnDefinitionRepository dmnDefinitionRepository,
        ObjectMapper objectMapper,
        com.zorrodev.bpm.engine.service.AdvisoryDeployLock advisoryDeployLock,
        @org.springframework.beans.factory.annotation.Value("${zorrobpm.engine.dmn-cache-max-size:500}") int maxSize,
        @org.springframework.beans.factory.annotation.Value("${zorrobpm.engine.dmn-cache-ttl-minutes:60}") int ttlMinutes) {
        this.feelEngineApi = feelEngineApi;
        this.dmnDefinitionRepository = dmnDefinitionRepository;
        this.objectMapper = objectMapper;
        this.advisoryDeployLock = advisoryDeployLock;
        this.parsedModelCache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(java.time.Duration.ofMinutes(ttlMinutes))
            .recordStats()
            .build();
    }

    /** Test-only accessor for the WO-AUDIT-3 P1 parse-counter POF (same package). */
    Cache<UUID, DmnDefinitionsModel> parsedModelCache() {
        return parsedModelCache;
    }

    private DmnDefinitionsModel parsedModel(UUID id, java.util.function.Supplier<String> xml) {
        return parsedModelCache.get(id,
            k -> SecureXmlParser.unmarshal(xml.get(), DmnDefinitionsModel.class));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deploy(String dmnXml) {
        deploy(dmnXml, null);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deploy(String dmnXml, UUID processDefinitionId) {
        deploy(dmnXml, processDefinitionId, null);
    }

    /**
     * WO-C8-18: same as {@link #deploy(String, UUID)}, but stamps {@code deploymentId} on the
     * created rows (batch deploys). Null keeps single-deploy behaviour byte-identical.
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deploy(String dmnXml, UUID processDefinitionId, UUID deploymentId) {
        DmnDefinitionsModel model = SecureXmlParser.unmarshal(dmnXml, DmnDefinitionsModel.class);
        if (model.getDecisions() == null || model.getDecisions().isEmpty()) {
            throw new EngineException("DMN resource has no decisions");
        }
        for (DmnDecisionModel decision : model.getDecisions()) {
            // WO-SCALE-1: serialize concurrent deploys of the same decisionId on the same
            // PG xact — the lock lives and dies with this deploy transaction, like the
            // original BPMN path (ProcessDefinitionVersioning).
            advisoryDeployLock.acquireForKey("dmn:" + decision.getId());
            int version = dmnDefinitionRepository.findFirstByDecisionIdOrderByVersionDesc(decision.getId())
                .map(e -> e.getVersion() + 1)
                .orElse(1);
            DmnDefinitionEntity entity = new DmnDefinitionEntity();
            entity.setId(java.util.UUID.randomUUID());
            entity.setDecisionId(decision.getId());
            entity.setVersionTag(Optional.ofNullable(decision.getExtensionElements())
                .map(DmnDecisionExtensionModel::getVersionTag)
                .map(DmnVersionTagModel::getValue)
                .filter(s -> !s.isBlank())
                .orElse(null));
            entity.setVersion(version);
            entity.setDmn(dmnXml);
            entity.setCreatedAt(Instant.now());
            entity.setProcessDefinitionId(processDefinitionId);
            entity.setDeploymentId(deploymentId);
            dmnDefinitionRepository.save(entity);
            // WO-AUDIT-3 (P1): the first evaluate must already hit — deploy parses once anyway.
            parsedModelCache.put(entity.getId(), model);
            log.info("Deployed DMN decision '{}' version {}", decision.getId(), version);
        }
    }

    @Override
    public Object evaluate(String decisionId, List<ProcessVariable> variables) {
        DmnDefinitionEntity entity = dmnDefinitionRepository.findFirstByDecisionIdOrderByVersionDesc(decisionId)
            .orElseThrow(() -> new EngineException("No deployed DMN decision '" + decisionId + "'"));
        return evaluateEntity(entity, decisionId, variables);
    }

    @Override
    public Object evaluate(String decisionId, List<ProcessVariable> variables, UUID pinnedProcessDefinitionId) {
        if (pinnedProcessDefinitionId == null) {
            return evaluate(decisionId, variables);
        }
        // NOTE: IllegalStateException, not EngineException, is deliberate here: the execution
        // dispatcher (ActivityServiceImpl.execute) rethrows EngineException as an abort, while any
        // other exception parks the token as an incident — and a missing pinned version must be
        // an incident with an explicit message (WO-C8-17 step 4), never a silent latest fallback.
        DmnDefinitionEntity entity = dmnDefinitionRepository
            .findFirstByDecisionIdAndProcessDefinitionIdOrderByVersionDesc(decisionId, pinnedProcessDefinitionId)
            .orElseThrow(() -> new IllegalStateException("DMN decision '" + decisionId
                + "' has no version deployed together with process version '" + pinnedProcessDefinitionId
                + "' (bindingType=\"deployment\") — deploy the decision bound to this process version"));
        return evaluateEntity(entity, decisionId, variables);
    }

    @Override
    public Object evaluateByVersionTag(String decisionId, List<ProcessVariable> variables, String versionTag) {
        // Same IllegalStateException mechanics as the deployment overload above (see its NOTE):
        // a missing id+tag pair is an incident with an explicit message, never a silent latest.
        DmnDefinitionEntity entity = dmnDefinitionRepository
            .findFirstByDecisionIdAndVersionTagOrderByVersionDesc(decisionId, versionTag)
            .orElseThrow(() -> new IllegalStateException("DMN decision '" + decisionId
                + "' has no deployed version annotated with version tag '" + versionTag
                + "' (bindingType=\"versionTag\") — deploy the decision carrying this tag"));
        return evaluateEntity(entity, decisionId, variables);
    }

    private Object evaluateEntity(DmnDefinitionEntity entity, String decisionId, List<ProcessVariable> variables) {
        DmnDefinitionsModel model = parsedModel(entity.getId(), entity::getDmn);
        DmnDecisionModel decision = model.getDecisions().stream()
            .filter(d -> decisionId.equals(d.getId()))
            .findFirst()
            .orElseThrow(() -> new EngineException("DMN resource has no decision '" + decisionId + "'"));
        return evaluateDecision(decision, model, toVariableMap(variables), new java.util.HashSet<>());
    }

    /**
     * WO-C8-10: evaluates one decision of a decision requirements graph. First resolves all
     * required decisions (recursively, with cycle detection) and extends {@code vars} with
     * their results bound under each required decision's id, then runs the pre-existing
     * table/literal logic unchanged. Decisions without {@code informationRequirement} behave
     * exactly as before the refactor.
     */
    private Object evaluateDecision(DmnDecisionModel decision, DmnDefinitionsModel model,
            Map<String, Object> vars, java.util.Set<String> resolving) {
        String decisionId = decision.getId();
        vars = resolveRequiredDecisions(decision, model, vars, resolving);
        DmnDecisionTableModel table = decision.getDecisionTable();
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

        String hitPolicy = table.getHitPolicy() == null ? "UNIQUE" : table.getHitPolicy().toUpperCase(java.util.Locale.ROOT);
        String aggregation = table.getAggregation() == null ? null : table.getAggregation().toUpperCase(java.util.Locale.ROOT);

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

    private Map<String, Object> resolveRequiredDecisions(DmnDecisionModel decision,
            DmnDefinitionsModel model, Map<String, Object> vars, java.util.Set<String> resolving) {
        if (decision.getInformationRequirements() == null || decision.getInformationRequirements().isEmpty()) {
            return vars;
        }
        if (!resolving.add(decision.getId())) {
            throw new EngineException("DMN decision requirements graph has a cycle involving '" + decision.getId() + "'");
        }
        Map<String, Object> extended = new LinkedHashMap<>(vars);
        for (InformationRequirementModel req : decision.getInformationRequirements()) {
            if (req.getRequiredDecision() == null) continue;
            String requiredId = req.getRequiredDecision().getId();
            DmnDecisionModel required = model.getDecisions().stream()
                .filter(d -> requiredId.equals(d.getId()))
                .findFirst()
                .orElseThrow(() -> new EngineException("DMN decision '" + decision.getId()
                    + "' requires undeployed decision '" + requiredId + "'"));
            Object result = evaluateDecision(required, model, extended, resolving);
            extended.put(requiredId, result);
        }
        resolving.remove(decision.getId());
        return extended;
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
        // WO-AUDIT-3 (P1): projection WITHOUT the TEXT dmn blob — keep the latest
        // version of each decisionId. Visibility semantics byte-identical to before
        // (same visibleMeta() on the same scope column), only the XML stays in the DB
        // until the per-latest-row load below.
        Map<String, DmnDefinitionRepository.DmnDecisionMeta> latest = new LinkedHashMap<>();
        for (DmnDefinitionRepository.DmnDecisionMeta m : dmnDefinitionRepository.findAllMeta()) {
            if (!visibleMeta(m, allowedPdIds)) {
                continue;
            }
            DmnDefinitionRepository.DmnDecisionMeta current = latest.get(m.getDecisionId());
            if (current == null || m.getVersion() > current.getVersion()) {
                latest.put(m.getDecisionId(), m);
            }
        }
        if (latest.isEmpty()) {
            return List.of();
        }
        // ONE query for the latest rows only (bounded by decision count, not versions).
        Map<UUID, DmnDefinitionEntity> byId = new java.util.HashMap<>();
        for (DmnDefinitionEntity e : dmnDefinitionRepository.findAllById(latest.values().stream()
                .map(DmnDefinitionRepository.DmnDecisionMeta::getId).toList())) {
            byId.put(e.getId(), e);
        }
        List<DmnDecision> result = new ArrayList<>();
        for (DmnDefinitionRepository.DmnDecisionMeta m : latest.values()) {
            DmnDefinitionEntity e = byId.get(m.getId());
            if (e == null) {
                continue; // deleted between the two reads — next list sees a consistent view
            }
            result.add(toDecisionDTO(e, parsedModel(e.getId(), e::getDmn)));
        }
        return result;
    }

    /** WO-AUDIT-3 (P1): visibility rule over the TEXT-less projection (WO-SEC-40:
     * a decision is visible iff its scoped process definition id is among the allowed
     * ones; {@code null} allowed = see all; unscoped decisions only for see-all). */
    private boolean visibleMeta(DmnDefinitionRepository.DmnDecisionMeta m, Collection<UUID> allowedPdIds) {
        if (allowedPdIds == null) {
            return true;
        }
        return m.getProcessDefinitionId() != null
            && allowedPdIds.contains(m.getProcessDefinitionId());
    }

    @Override
    public Optional<UUID> findProcessDefinitionId(String decisionId) {
        return dmnDefinitionRepository.findLatestProcessDefinitionId(decisionId);
    }

    @Override
    public DmnDecision getDecision(String decisionId) {
        DmnDefinitionEntity entity = dmnDefinitionRepository.findFirstByDecisionIdOrderByVersionDesc(decisionId)
            .orElseThrow(() -> new EngineException("No deployed DMN decision '" + decisionId + "'"));
        return toDecisionDTO(entity, parsedModel(entity.getId(), entity::getDmn));
    }

    private DmnDecision toDecisionDTO(DmnDefinitionEntity entity, DmnDefinitionsModel model) {
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
            dto.setHitPolicy(table.getHitPolicy() == null ? "UNIQUE" : table.getHitPolicy().toUpperCase(java.util.Locale.ROOT));
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
