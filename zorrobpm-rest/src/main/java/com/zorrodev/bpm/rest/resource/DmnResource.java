package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.DmnContract;
import com.zorrodev.bpm.contract.dto.DeployDmnDTO;
import com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.DmnDecision;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DmnService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Valid;

@RestController
@RequiredArgsConstructor
@Slf4j
public class DmnResource implements DmnContract {

    private final DmnService dmnService;
    private final EventAuthzResolver eventAuthzResolver;
    private final HttpServletRequest request;
    private final HttpServletResponse httpResponse;
    private final AuditLogService auditLogService;

    /**
     * WO-SEC-40: resolves the process definition ids the current principal may access.
     * {@code null} = see all (superAdmin / full-grant), empty = see nothing (DENY).
     * Reuses the shared EventAuthzResolver (same mechanism as ProcessDefinitionResource).
     */
    private Collection<UUID> resolveAllowedPdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of();
        }
        return eventAuthzResolver.visibleDefinitionIds(principal, null);
    }

    /**
     * WO-SEC-40: throws 404 if the current principal cannot access the decision's owning
     * process definition. 404 (not 403) keeps decision existence hidden from unauthorized
     * principals (same pattern as ProcessDefinitionResource.requirePdAccess).
     *
     * <p>WO-AUDIT-17 (аудит F-21): это ДВЕ ПОЛИТИКИ, и их нельзя смешивать.
     * Определения (в том числе DMN-решения) видны по ADR-8 §1 —
     * {@code visibleDefinitionIds}; runtime-данные инстансов — по
     * {@code readableRuntimePdIds}. Этот гейт спрашивает про ОПРЕДЕЛЕНИЕ,
     * поэтому он и спрашивает {@code visibleDefinitionIds}.
     */
    private void requireDecisionAccess(String decisionId) {
        Collection<UUID> allowed = resolveAllowedPdIds();
        if (allowed == null) {
            return; // see all (superAdmin / full grant)
        }
        UUID owningPdId = dmnService.findProcessDefinitionId(decisionId).orElse(null);
        if (owningPdId == null || !allowed.contains(owningPdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision not found");
        }
    }

    @Override
    public List<DmnDecision> getDecisions() {
        return dmnService.listDecisions(resolveAllowedPdIds());
    }

    /**
     * WO-C8-15 (A-7): DMN deploy — the missing production path for getting decisions into the
     * engine (previously only tests called {@code DmnService.deploy}).
     * ADR-2, exactly like BPMN deploy ({@code ProcessDefinitionResource.addProcessDefinition}):
     * SUPER_ADMIN only — no principal → 401, non-admin → 403. An endpoint uploading executable
     * logic must not be weaker than the BPMN one.
     */
    /**
     * WO-API-1 (API-1): create → 201 + Location (контракт не тронут).
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public List<DmnDecision> deployDmn(@Valid @RequestBody DeployDmnDTO dto) {
        requireSuperAdmin();
        Map<String, Integer> before;
        try {
            before = maxVersions(dmnService.listDecisions(null));
            dmnService.deploy(dto.getDmn(), dto.getProcessDefinitionId());
        } catch (EngineException e) {
            log.warn("DMN deploy failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DMN deployment failed");
        }
        List<DmnDecision> after = dmnService.listDecisions(null);
        // WO-C8-17 (debt from WO-C8-15): audit every newly deployed decision version, like
        // BPMN deploy does in both its places. One record per decision version —
        // key = decisionId, target = decisionId:vN (the entity UUID is not exposed in the DTO).
        // WO-QW-8: the SAME «version actually advanced» filter also scopes the response
        // below — the list carries old catalog rows too, and returning them as «deployed»
        // misleads callers (Location pointed at the catalog's first row, not this deploy).
        List<DmnDecision> deployed = new java.util.ArrayList<>();
        for (DmnDecision decision : after) {
            if (decision.getVersion() > before.getOrDefault(decision.getId(), 0)) {
                deployed.add(decision);
            }
        }
        for (DmnDecision decision : deployed) {
            auditLogService.record(getPrincipal(), "DEPLOY", decision.getId(),
                decision.getId() + ":v" + decision.getVersion());
        }
        // WO-API-1: Location по первой задеплоенной decision (nullable-guard как везде).
        // WO-QW-8: из реально задеплоенных этим вызовом, не из всего каталога.
        if (httpResponse != null && !deployed.isEmpty()) {
            httpResponse.setHeader("Location", "/dmn/" + deployed.get(0).getId());
        }
        return deployed;
    }

    private static Map<String, Integer> maxVersions(List<DmnDecision> decisions) {
        Map<String, Integer> max = new HashMap<>();
        for (DmnDecision decision : decisions) {
            max.merge(decision.getId(), decision.getVersion(), Math::max);
        }
        return max;
    }

    /**
     * ADR-2 mechanism, copied 1:1 from {@code ProcessDefinitionResource.requireSuperAdmin} —
     * the same codes for the same cases (WO-C8-15: no weaker auth on the DMN path).
     */
    private void requireSuperAdmin() {
        Principal principal = getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deploy requires SUPER_ADMIN");
        }
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }

    @Override
    public DmnDecision getDecision(@PathVariable String decisionId) {
        requireDecisionAccess(decisionId);
        try {
            return dmnService.getDecision(decisionId);
        } catch (EngineException e) {
            log.warn("DMN decision not found: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision not found");
        }
    }

    /**
     * DMN evaluate — <b>ЧТЕНИЕ ОПРЕДЕЛЕНИЯ</b> (политика зафиксирована решением maintainer
     * 2026-10-21, аудит F-21; оформлена и закреплена тестом в WO-AUDIT-17).
     *
     * <p><b>Что делает.</b> evaluate — ЧИСТАЯ ФУНКЦИЯ над переданными переменными:
     * читает опубликованное ОПРЕДЕЛЕНИЕ решения (строку {@code dmn_definitions}) и считает
     * таблицу решений в FEEL. Побочных эффектов нет: данные инстансов evaluate НЕ читает
     * и НЕ пишет. Это видно и по поверхности эндпоинта (во входе нет ссылки на инстанс),
     * и по коду: {@code DmnService.evaluate(decisionId, variables)} ходит только в
     * {@code dmn_definitions} (см. {@code DmnServiceImpl.evaluateEntity}).
     *
     * <p><b>Кто может вызвать.</b> Видимость ОПРЕДЕЛЕНИЙ — ADR-8 §1
     * ({@link EventAuthzResolver#visibleDefinitionIds}):
     * <ul>
     *   <li>{@code UserPrincipal} — обычный аутентифицированный пользователь, в том числе
     *       НЕ участник процесса: видит ВСЕ определения (ADR-8 §1: «видит все процессы» =
     *       только определения; runtime-данные чужих процессов не видны) → 200 + результат;</li>
     *   <li>{@code ServicePrincipal} (API-ключ) — видимость остаётся grant-scoped: тот же
     *       резолвер, что {@code readableRuntimePdIds} (для сервисного ключа обе ветки
     *       ADR-8 дают одно и то же множество — гранты владельца ключа). Нет гранта на
     *       процесс-владелец решения → 404 (существование решения скрыто);</li>
     *   <li>{@code SUPER_ADMIN} — видит всё;</li>
     *   <li>без аутентификации — 401.</li>
     * </ul>
     *
     * <p><b>Почему так.</b> DMN-решение — исполняемая логика ВЛАДЕЛЬЦА модели, а не данные
     * его процессов: подстановка чужих переменных не раскрывает чужих данных — данных
     * инстансов evaluate не видит вообще. Переход на runtime-семантику
     * ({@code readableRuntimePdIds}) ломал бы консьюмерские вызовы без выигрыша по
     * безопасности — решение maintainer 2026-10-21 (F-21). Расход CPU на вычисление закрыт
     * rate-limit'ом ({@code /dmn} — DATA-бакет, WO-AUDIT-13) и лимитом размера запроса
     * (5 МБ, {@code DeployDmnDTO.MAX_DMN_LENGTH}).
     *
     * <p>Политика продублирована в {@code docs/reference/security.md}; закреплена тестом
     * {@code DmnEvaluatePolicyIntegrationTests} (WO-AUDIT-17).
     */
    @Operation(summary = "Evaluate a DMN decision (reading a definition)",
        description = """
            Evaluates a deployed DMN decision as a PURE FUNCTION over the variables passed in \
            the request: the decision DEFINITION is read, process-instance data is neither read \
            nor written.

            Authorization follows ADR-8 §1 (definition visibility, \
            EventAuthzResolver.visibleDefinitionIds): any authenticated UserPrincipal may read \
            every definition, including a decision of a process it is not a member of (200 + \
            result). A ServicePrincipal (API key) stays grant-scoped — the same resolution as \
            readableRuntimePdIds: no grant on the process owning the decision → 404 (existence \
            hidden). SUPER_ADMIN sees all; an unauthenticated call → 401.

            Policy: decision maintainer 2026-10-21 (audit F-21), documented in \
            docs/reference/security.md, pinned by DmnEvaluatePolicyIntegrationTests.""")
    @Override
    public Object evaluateDecision(@PathVariable String decisionId, @Valid @RequestBody EvaluateDecisionDTO dto) {
        requireDecisionAccess(decisionId);
        Object result;
        try {
            result = dmnService.evaluate(decisionId, dto.getVariables());
        } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException e) {
            // WO-ENG-24: временная перегрузка пула — не ошибка решения.
            // Проброс до 503-хендлера, а не 400-маппинг ниже («Decision
            // evaluation failed» вводил бы в заблуждение «не повторять»).
            throw e;
        } catch (EngineException e) {
            log.warn("DMN evaluation error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision evaluation failed");
        }
        // always return a name -> value object for the UI; a single-output decision is wrapped under "result"
        return result instanceof Map ? result : Map.of("result", result == null ? "" : result);
    }
}
