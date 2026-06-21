package com.zorrodev.bpm.rest.service;

import com.zorrodev.bpm.rest.dto.DashboardDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public DashboardDTO getDashboard() {
        DashboardDTO dto = new DashboardDTO();

        dto.setActiveProcessInstances(countActiveProcessInstances());
        dto.setOpenUserTasks(countOpenUserTasks());
        dto.setOpenServiceTasks(countOpenServiceTasks());
        dto.setOpenIncidents(countOpenIncidents());
        dto.setCompletedToday(countCompletedToday());
        dto.setTotalProcessDefinitions(countProcessDefinitions());

        dto.setRecentDefinitions(getRecentDefinitions(5));
        dto.setRecentInstances(getRecentInstances(5));
        dto.setRecentIncidents(getRecentIncidents(5));

        return dto;
    }

    private long countActiveProcessInstances() {
        var result = em.createQuery(
            "SELECT COUNT(pi) FROM ProcessInstanceEntity pi WHERE pi.completedAt IS NULL",
            Long.class
        ).getSingleResult();
        return result != null ? result : 0L;
    }

    private long countOpenUserTasks() {
        var result = em.createQuery(
            "SELECT COUNT(ut) FROM UserTaskEntity ut WHERE ut.completedAt IS NULL",
            Long.class
        ).getSingleResult();
        return result != null ? result : 0L;
    }

    private long countOpenServiceTasks() {
        var result = em.createQuery(
            "SELECT COUNT(st) FROM ServiceTaskEntity st WHERE st.completedAt IS NULL",
            Long.class
        ).getSingleResult();
        return result != null ? result : 0L;
    }

    private long countOpenIncidents() {
        var result = em.createQuery(
            "SELECT COUNT(i) FROM IncidentEntity i WHERE i.completedAt IS NULL",
            Long.class
        ).getSingleResult();
        return result != null ? result : 0L;
    }

    private long countCompletedToday() {
        Instant startOfDay = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
        var result = em.createQuery(
            "SELECT COUNT(pi) FROM ProcessInstanceEntity pi WHERE pi.completedAt >= :startOfDay",
            Long.class
        ).setParameter("startOfDay", startOfDay).getSingleResult();
        return result != null ? result : 0L;
    }

    private long countProcessDefinitions() {
        var result = em.createQuery(
            "SELECT COUNT(pd) FROM ProcessDefinitionEntity pd",
            Long.class
        ).getSingleResult();
        return result != null ? result : 0L;
    }

    private List<DashboardDTO.ProcessDefinitionSummary> getRecentDefinitions(int limit) {
        List<Object[]> rows = em.createQuery(
            "SELECT pd.id, pd.key, pd.name, pd.version, pd.createdAt " +
            "FROM ProcessDefinitionEntity pd ORDER BY pd.createdAt DESC",
            Object[].class
        ).setMaxResults(limit).getResultList();

        return rows.stream().map(r -> new DashboardDTO.ProcessDefinitionSummary(
            (UUID) r[0],
            (String) r[1],
            (String) r[2],
            (Integer) r[3],
            (Instant) r[4]
        )).toList();
    }

    private List<DashboardDTO.ProcessInstanceSummary> getRecentInstances(int limit) {
        List<Object[]> rows = em.createQuery(
            "SELECT pi.id, pi.processDefinitionId, pd.name, pi.startedAt, pi.completedAt " +
            "FROM ProcessInstanceEntity pi " +
            "LEFT JOIN ProcessDefinitionEntity pd ON pi.processDefinitionId = pd.id " +
            "ORDER BY pi.startedAt DESC",
            Object[].class
        ).setMaxResults(limit).getResultList();

        return rows.stream().map(r -> new DashboardDTO.ProcessInstanceSummary(
            (UUID) r[0],
            (UUID) r[1],
            (String) r[2],
            (Instant) r[3],
            (Instant) r[4]
        )).toList();
    }

    private List<DashboardDTO.IncidentSummary> getRecentIncidents(int limit) {
        List<Object[]> rows = em.createQuery(
            "SELECT i.id, i.message, i.createdAt, i.completedAt " +
            "FROM IncidentEntity i ORDER BY i.createdAt DESC",
            Object[].class
        ).setMaxResults(limit).getResultList();

        return rows.stream().map(r -> new DashboardDTO.IncidentSummary(
            (UUID) r[0],
            (String) r[1],
            (Instant) r[2],
            (Instant) r[3]
        )).toList();
    }
}
