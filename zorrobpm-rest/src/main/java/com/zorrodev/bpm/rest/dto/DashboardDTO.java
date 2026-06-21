package com.zorrodev.bpm.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDTO {

    private long activeProcessInstances;
    private long openUserTasks;
    private long openServiceTasks;
    private long openIncidents;
    private long completedToday;
    private long totalProcessDefinitions;

    private List<ProcessDefinitionSummary> recentDefinitions;
    private List<ProcessInstanceSummary> recentInstances;
    private List<IncidentSummary> recentIncidents;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessDefinitionSummary {
        private UUID id;
        private String key;
        private String name;
        private Integer version;
        private Instant createdAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessInstanceSummary {
        private UUID id;
        private UUID processDefinitionId;
        private String processDefinitionName;
        private Instant startedAt;
        private Instant completedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IncidentSummary {
        private UUID id;
        private String message;
        private Instant createdAt;
        private Instant completedAt;
    }
}
