package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RuntimeContract;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RuntimeResource implements RuntimeContract {

    private final RuntimeService runtimeService;
    private final UserTaskRepository userTaskRepository;
    private final DBService dbService;
    private final HttpServletRequest request;

    @Transactional
    @Override
    public IdDTO startProcessInstance(@Valid @RequestBody StartProcessInstanceDTO dto) {
        return Optional.ofNullable(runtimeService.startProcessInstance(dto)).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO completeServiceTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        return Optional.ofNullable(runtimeService.completeServiceTask(id, dto.getVariables())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO failServiceTask(@PathVariable UUID id, @RequestBody FailServiceTaskDTO dto) {
        return Optional.ofNullable(runtimeService.failServiceTask(id, dto.getMessage(), dto.getRetries())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        checkAssignee(id);
        return Optional.ofNullable(runtimeService.completeUserTask(id, dto.getVariables())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO resolveIncident(@PathVariable UUID id, @RequestBody ResolveIncidentDTO dto) {
        return Optional.ofNullable(runtimeService.resolveIncident(id, dto.getVariables())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO cancelProcessInstance(@PathVariable UUID id) {
        var pi = dbService.getProcessInstance(id);
        if (pi.getCompletedAt() != null || pi.isCancelled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Process instance already completed or cancelled");
        }
        dbService.cancelActiveActivities(id);
        dbService.deleteTimerJobsByProcessInstanceId(id);
        dbService.deleteMessageSubscriptionsByProcessInstanceId(id);
        dbService.cancelProcessInstance(id);
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    private void checkAssignee(UUID taskId) {
        TokenService.Claims claims = (TokenService.Claims) request.getAttribute("authClaims");
        if (claims == null) return;

        // ADMIN can complete any task
        if ("ADMIN".equals(claims.role())) return;

        UserTaskEntity task = userTaskRepository.findById(taskId).orElse(null);
        if (task == null) return;

        // Unassigned task — any user can complete
        if (task.getAssignee() == null || task.getAssignee().isBlank()) return;

        // Assignee matches — allowed
        if (task.getAssignee().equals(claims.username())) return;

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Task is assigned to another user");
    }

    private IdDTO toDTO(com.zorrodev.bpm.engine.dto.IdDTO idDTO) {
        IdDTO result = new IdDTO();
        result.setId(idDTO.getId());
        return result;
    }
}
