package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.service.db.ActivityDbOperations;
import com.zorrodev.bpm.engine.service.db.IncidentDbOperations;
import com.zorrodev.bpm.engine.service.db.MessageSubscriptionDbOperations;
import com.zorrodev.bpm.engine.service.db.ProcessInstanceDbOperations;
import com.zorrodev.bpm.engine.service.db.TimerDbOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * WO-AUDIT-12 (F-02): каскадная отмена дерева инстансов.
 *
 * <p>Найдено аудитом ({@code docs/audit/ZorroBPM-full-audit-2026-10-19.md},
 * F-02): операторская отмена инстанса гасит ТОЛЬКО его самого. Дочерние
 * инстансы, поднятые call activity, остаются жить: их задачи можно завершить
 * спустя часы или дни, и тогда завершение ребёнка (а) переписывало историю
 * отменённого родителя (CANCELLED → COMPLETED), (б) продолжало поток
 * отменённого процесса (новые activity-строки в завершённом инстансе) и
 * (в) шло вторым {@code processCompleted}.
 *
 * <p>Здесь — алгоритм «убить инстанс каскадом по дереву call activity».
 * Вызывается он из {@code DBService.cancelProcessInstance} — единого
 * choke-point ОТМЕНЫ (её зовут и REST-операторская отмена, и внутренний
 * deferred-хвост canceling-фазы). Существующие kill-пути дочерних инстансов
 * ({@code EventTrigger.cancelScopeContainer} — прерывающая граница на
 * контейнере, {@code ErrorEscalationThrower} — вылет error/escalation из
 * дочернего инстанса) в этот сервис НЕ заведены: они убивают ребёнка точечно
 * (свой scope), а не всё дерево. Terminate-путь (корневой end event с
 * terminate) тоже сюда не заведён — при terminate живой дочерний инстанс
 * остаётся жить (поведение до этой правки и согласованное с Camunda 8:
 * terminate завершает текущий инстанс, а не дерево). Вредные симптомы
 * (переписывание истории отменённого родителя, продолжение его потока) в
 * этих путях закрыты guard'ом «родитель терминален» и условным
 * {@code completeActivity}; решение про terminate — за maintainer (в отчёте
 * WO-AUDIT-12 §7, находка 2).
 *
 * <p>Порядок: сначала терминальная пометка РОДИТЕЛЯ (её делает вызывающий),
 * потом дети. Причина — guard в {@code FlowNavigator}: продолжение ребёнка
 * глушится, если родитель терминален; значит, родитель должен стать
 * терминальным ДО того, как мы начнём трогать строки детей.
 *
 * <p>Локи: instance-lock родителя берёт вызывающий (путь отмены) — дети
 * наследуют эту транзакцию. Свой instance-lock ребёнка НЕ берётся
 * осознанно: путь завершения ребёнка держит lock ребёнка и ЗАПРАШИВАЕТ lock
 * родителя (child→parent), а отмена держит lock родителя и просила бы lock
 * ребёнка (parent→child) — классический ABBA-порядок, из которого есть
 * взаимный wait. Существующие kill-пути дочерних инстансов
 * ({@code EventTrigger.cancelScopeContainer},
 * {@code ErrorEscalationThrower}) сделаны так же — без lock'а ребёнка.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceCancellationService {

    private final ProcessInstanceDbOperations processInstanceDbOperations;
    private final ActivityDbOperations activityDbOperations;
    private final IncidentDbOperations incidentDbOperations;
    private final TimerDbOperations timerDbOperations;
    private final MessageSubscriptionDbOperations messageSubscriptionDbOperations;

    /**
     * Убивает всех ещё живых потомков инстанса (дерево call activity) —
     * рекурсивно, дети глубже — раньше — раньше их родителей.
     *
     * <p>Сам инстанс НЕ трогается: его терминальную пометку делает вызывающий
     * (тот же порядок «родитель терминален → потом дети»).
     */
    public void cancelDescendants(UUID processInstanceId) {
        List<UUID> children = processInstanceDbOperations
            .findRunningChildInstanceIdsByParentInstance(processInstanceId);
        for (UUID childId : children) {
            // Сначала внуки: ребёнок должен умереть вместе со своим потомством.
            cancelDescendants(childId);
            kill(childId);
        }
        if (!children.isEmpty()) {
            log.info("{}: cascade cancel killed {} child instance(s)", processInstanceId, children.size());
        }
    }

    /**
     * Терминальная остановка одного инстанса — тот же набор, что у отмены
     * корневого инстанса.
     */
    private void kill(UUID processInstanceId) {
        activityDbOperations.cancelActiveActivities(processInstanceId);
        // WO-C8-49: ERROR-активити → CANCELLED и открытые инциденты закрыты
        // (та же финализация, что у корневого инстанса).
        activityDbOperations.cancelErrorActivities(processInstanceId);
        incidentDbOperations.completeOpenIncidentsByProcessInstanceId(processInstanceId);
        timerDbOperations.deleteTimerJobsByProcessInstanceId(processInstanceId);
        messageSubscriptionDbOperations.deleteMessageSubscriptionsByProcessInstanceId(processInstanceId);
        processInstanceDbOperations.cancelProcessInstance(processInstanceId);
    }
}
