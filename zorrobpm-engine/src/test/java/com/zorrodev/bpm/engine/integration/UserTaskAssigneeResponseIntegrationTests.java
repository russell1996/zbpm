package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-IN-4: multi-instance user task с числовыми employeeId (как у внешней команды:
 * {@code assignee="=assigneeEmployeeId"}, parallel MI, два исполнителя).
 *
 * <p>RED на коде до WO-IN-4: {@code GET /user-tasks} не отдаёт {@code assignee}
 * (поля нет в DTO), фильтр {@code ?assignee=X} у внешней команды давал total 0.
 * Тест фиксирует ОБА факта плюс то, что реально лежит в колонке.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UserTaskAssigneeResponseIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private UserTaskRepository userTaskRepository;

    private static ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    private UUID startMi() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-in4-mi-assignee.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        // Числовые employeeId как у внешней команды (21346-подобные).
        dto.setVariables(List.of(
            var("employees", ProcessVariableType.JSON, "[21346, 78901]"),
            var("candidateGroup", ProcessVariableType.STRING, "sales")));
        return runtimeService.startProcessInstance(dto).getId();
    }

    private UserTaskQuery query(UUID pi) {
        UserTaskQuery q = new UserTaskQuery();
        q.setProcessInstanceId(pi);
        return q;
    }

    @Transactional
    @Test
    void criterion1_listAndDetailExposeAssignee() throws Exception {
        UUID pi = startMi();
        List<UserTask> tasks = queryService.findUserTasks(query(pi), null).getData();
        assertThat(tasks).hasSize(2);

        // RED: поля assignee нет в ответе — оба экземпляра неразличимы.
        assertThat(tasks).extracting(UserTask::getAssignee)
            .containsExactlyInAnyOrder("21346", "78901");

        UUID oneId = tasks.get(0).getId();
        UserTask detail = queryService.getUserTask(oneId);
        assertThat(detail.getAssignee()).isEqualTo(tasks.get(0).getAssignee());
    }

    @Transactional
    @Test
    void criterion2_filterByAssigneeReturnsExactlyOneRowPerExecutor() throws Exception {
        UUID pi = startMi();
        for (String executor : List.of("21346", "78901")) {
            UserTaskQuery q = query(pi);
            q.setAssignee(executor);
            List<UserTask> rows = queryService.findUserTasks(q, null).getData();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getAssignee()).isEqualTo(executor);
        }
    }

    @Transactional
    @Test
    void criterion3_columnContentIsThePlainNumber() throws Exception {
        UUID pi = startMi();
        List<String> stored = userTaskRepository.findByProcessInstanceId(pi).stream()
            .map(UserTaskEntity::getAssignee)
            .sorted()
            .toList();
        // Причина total:0 у внешней команды — если здесь "21346.0"/"21,346",
        // точное совпадение фильтра никогда не сработает. Фиксируем факт.
        assertThat(stored).containsExactly("21346", "78901");
    }

    @Transactional
    @Test
    void criterion4_candidateGroupsExposed() throws Exception {
        UUID pi = startMi();
        List<UserTask> tasks = queryService.findUserTasks(query(pi), null).getData();
        assertThat(tasks).hasSize(2);
        assertThat(tasks.stream().map(UserTask::getCandidateGroups).toList())
            .containsExactlyInAnyOrder(List.of("sales"), List.of("sales"));
    }

    @Transactional
    @Test
    void criterion5_plainTaskWithoutAssigneeExposesNullAndEmptyLists() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-usertask-query.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        List<UserTask> tasks = queryService.findUserTasks(query(pi), null).getData();
        assertThat(tasks).hasSize(1);
        // Обычная задача с assignee="alice": поле есть; кандидатов нет → пустые списки, не null.
        assertThat(tasks.get(0).getAssignee()).isEqualTo("alice");
        assertThat(tasks.get(0).getCandidateGroups()).isEmpty();
        assertThat(tasks.get(0).getCandidateUsers()).isEmpty();
        UserTask detail = queryService.getUserTask(tasks.get(0).getId());
        assertThat(detail.getAssignee()).isEqualTo("alice");
        assertThat(detail.getCandidateGroups()).isEmpty();
        assertThat(detail.getCandidateUsers()).isEmpty();
    }

    @Transactional
    @Test
    void criterion6_candidateUsersExposed() throws Exception {
        UUID pi = startMi();
        List<UserTask> tasks = queryService.findUserTasks(query(pi), null).getData();
        assertThat(tasks).hasSize(2);
        // candidateUsers="reviewer" (литерал): оба экземпляра несут его списком.
        assertThat(tasks.stream().map(UserTask::getCandidateUsers).toList())
            .containsExactlyInAnyOrder(List.of("reviewer"), List.of("reviewer"));
        // И фильтр ?candidateUser=reviewer находит обе строки (согласованность с IN-3).
        UserTaskQuery q = query(pi);
        q.setCandidateUser("reviewer");
        assertThat(queryService.findUserTasks(q, null).getData()).hasSize(2);
    }
}
