package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-63: P-46-якорь на {@code openForActivity} — девятое место с
 * activity-only захватом, не перечисленное в WO (найдено grep'ом при разборе).
 *
 * <p>Сегодня оба вызывающих (cancel-путь и {@code fireBoundary}) уже держат
 * instance-lock, поэтому для них перевод на {@code lockInstanceFirst} — повторный
 * захват своей же строки, поведение не меняется. Смысл в том, чтобы ни один
 * будущий вызов не принёс обратно activity-first: WO-REL-59 и WO-REL-63 — одна
 * и та же мина, два раза.
 */
@ExtendWith(MockitoExtension.class)
class CancelingPhaseServiceTest {

    @Mock
    private DBService dbService;
    @Mock
    private BpmnService bpmnService;
    @Mock
    private ElementSupport elementSupport;
    @Mock
    private ServiceTaskEnqueueService serviceTaskEnqueueService;
    @Mock
    private ActivityRepository activityRepository;
    @Mock
    private BpmnProcessDefinitionModel bpmn;

    @InjectMocks
    private CancelingPhaseService cancelingPhaseService;

    @Test
    void openForActivity_takesInstanceLock() {
        UUID activityId = UUID.randomUUID();
        UUID piId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setProcessInstanceId(piId);
        activity.setStatus(ActivityStatus.CANCELLED);
        activity.setType(BpmnElementType.USER_TASK);
        when(elementSupport.lockInstanceFirst(activityId)).thenReturn(activity);

        ProcessInstance pi = new ProcessInstance();
        pi.setProcessDefinitionId(UUID.randomUUID());
        when(dbService.getProcessInstance(piId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId())).thenReturn(bpmn);
        BpmnElementModel element = new BpmnElementModel();
        element.setId("userTask1");
        when(bpmn.getElement(activity.getBpmnElementId())).thenReturn(element);
        // без canceling-листенеров фаза не открывается — до этого места и не доходим
        when(elementSupport.userTaskCancelingListeners(element)).thenReturn(List.of());

        assertThat(cancelingPhaseService.openForActivity(activityId, null))
            .as("без canceling-листенеров фаза не открывается")
            .isFalse();

        verify(elementSupport, times(1)).lockInstanceFirst(activityId);
        verify(dbService, never()).getActivityForUpdate(any());
    }
}
