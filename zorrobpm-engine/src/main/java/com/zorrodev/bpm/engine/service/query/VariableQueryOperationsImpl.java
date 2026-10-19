package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.VariableScopeGroup;
import com.zorrodev.bpm.contract.model.VariableScopeInfo;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.mapper.VariableMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VariableQueryOperationsImpl implements VariableQueryOperations {

    private final VariableRepository variableRepository;
    private final VariableMapper variableMapper;
    private final QueryPaginationSupport queryPaginationSupport;
    private final ActivityRepository activityRepository;
    private final TokenRepository tokenRepository;

    @Override
    public PagedDataDTO<ProcessVariable> findVariables(VariableQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<ProcessVariableEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return queryPaginationSupport.emptyPage(query);
        }
        if (allowedPdIds != null) {
            specifications.add(queryPaginationSupport.processInstanceInAllowedDefinitions(allowedPdIds));
        }
        if (query.getProcessInstanceId() != null) {
            specifications.add(VariableRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getName() != null) {
            specifications.add(VariableRepository.byName(query.getName()));
        }
        if (query.getType() != null) {
            specifications.add(VariableRepository.byType(query.getType()));
        }
        if (query.getValue() != null) {
            specifications.add(VariableRepository.byValue(query.getValue()));
        }
        // WO-ENG-14: activity-scoped ioMapping variables must not leak into
        // the process variable list — default is root scope only.
        if (query.getActivityId() != null) {
            specifications.add(VariableRepository.byScopeId(query.getActivityId()));
        } else {
            specifications.add(VariableRepository.byRootScope());
        }
        // WO-DIFF-3 (#5): the MI join-bookkeeping row (_mi_batch_<miId>) is an
        // engine-internal detail (WO-ENG-7) — never part of the externally
        // visible variable list, exactly like Zeebe/Raxon expose no such
        // variable. The row itself stays in storage (the join reads it via
        // the pinpoint getVariableTextValue, not via this query).
        specifications.add(Specification.not(VariableRepository.byNamePrefix("_mi_batch_")));
        Specification<ProcessVariableEntity> all = Specification.allOf(specifications);
        return queryPaginationSupport.toDTO(variableRepository.findAll(all, queryPaginationSupport.clampedPage(query.getPageIndex(), query.getPageSize(), Sort.unsorted())), variableMapper::toDTO);
    }

    /**
     * WO-C8-51 (часть A): все переменные инстанса, разложенные по скоупам БЕЗ
     * слияния: root-группа ({@code scope == null}) всегда первая, дальше по
     * группе на каждый непустой scopeId — в порядке создания activity-строки
     * ({@code createdAt}), при равенстве — по строковому id активности (два
     * MI-инстанса спаунятся в одну микросекунду). Внутри группы — по
     * (имя, значение). Контракт групп:
     * <ul>
     *   <li>строки отдаются СЫРЫМИ — включая engine-internal {@code _mi_batch_<miId>},
     *       который {@link #findVariables} (внешний {@code /variables}) намеренно
     *       скрывает: отладчику нужен полный состав скоупа, а не внешний вид;</li>
     *   <li>значения — через тот же {@link VariableMapper}, что у {@code /variables}
     *       (одинаковое усечение/маскирование, см. «Вне WO» в отчёте C8-51);</li>
     *   <li>triple-запросная загрузка: переменные, активности, токены — по одному
     *       {@code findAllById} на таблицу (без N+1 на группу).</li>
     * </ul>
     */
    @Override
    public List<VariableScopeGroup> variablesByScope(UUID processInstanceId) {
        List<ProcessVariableEntity> rows = variableRepository.findByProcessInstanceId(processInstanceId);

        List<UUID> scopeIds = rows.stream()
            .map(ProcessVariableEntity::getScopeId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<UUID, ScopeDescriptor> scopes = describeScopes(scopeIds);

        List<VariableScopeGroup> groups = new ArrayList<>();
        groups.add(group(null, rows));
        scopeIds.stream()
            .sorted(Comparator
                .comparing((UUID id) -> scopes.get(id).createdAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(UUID::toString))
            .forEach(id -> groups.add(group(scopes.get(id).info(), rows)));
        return groups;
    }

    /**
     * Описание скоупов: activity-строка даёт bpmn-элемент/тип/статус и createdAt,
     * её токен — родительский скоуп. Отсутствующая activity (скоуп осиротел)
     * остаётся видимой группой: {@code scope.activityId} заполнен, остальное null —
     * такую группу нельзя спутать с root ({@code scope == null}).
     */
    private Map<UUID, ScopeDescriptor> describeScopes(List<UUID> scopeIds) {
        if (scopeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ActivityEntity> activities = activityRepository.findAllById(scopeIds).stream()
            .collect(Collectors.toMap(ActivityEntity::getId, a -> a));
        Set<UUID> tokenIds = activities.values().stream()
            .map(ActivityEntity::getToken)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, TokenEntity> tokens = tokenRepository.findAllById(tokenIds).stream()
            .collect(Collectors.toMap(TokenEntity::getId, t -> t));

        Map<UUID, ScopeDescriptor> described = new LinkedHashMap<>();
        for (UUID scopeId : scopeIds) {
            VariableScopeInfo info = new VariableScopeInfo();
            info.setActivityId(scopeId);
            Instant createdAt = null;
            ActivityEntity activity = activities.get(scopeId);
            if (activity != null) {
                info.setBpmnElementId(activity.getBpmnElementId());
                info.setElementType(activity.getType() == null ? null : activity.getType().name());
                info.setStatus(activity.getStatus() == null ? null : activity.getStatus().name());
                createdAt = activity.getCreatedAt();
                TokenEntity token = activity.getToken() == null ? null : tokens.get(activity.getToken());
                if (token != null) {
                    info.setParentScopeActivityId(token.getScopeActivityId());
                }
            }
            described.put(scopeId, new ScopeDescriptor(info, createdAt));
        }
        return described;
    }

    /** Строки одной группы: {@code null}-описание = root ({@code scopeId IS NULL}). */
    private VariableScopeGroup group(VariableScopeInfo info, List<ProcessVariableEntity> rows) {
        UUID scopeId = info == null ? null : info.getActivityId();
        VariableScopeGroup group = new VariableScopeGroup();
        group.setScope(info);
        group.setVariables(rows.stream()
            .filter(row -> Objects.equals(row.getScopeId(), scopeId))
            .sorted(Comparator
                .comparing(ProcessVariableEntity::getName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProcessVariableEntity::getTextValue, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(variableMapper::toDTO)
            .toList());
        return group;
    }

    /** Внутренняя пара «описание скоупа + время создания его activity» (для порядковой сортировки). */
    private record ScopeDescriptor(VariableScopeInfo info, Instant createdAt) {
    }
}
