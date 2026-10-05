package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class JobDetailModel {
    private UUID serviceTaskId;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String serviceTaskKey;
    private String job;
    private Map<String, ProcessVariable> variables;
    /** Custom headers from {@code zeebe:taskHeaders} (WO-C8-7) — null when the task declares none. */
    private Map<String, String> taskHeaders;
    /** Job priority from {@code zeebe:jobPriorityDefinition} (WO-C8-9, corrected WO-C8-13/A-1 — activation order hint) — null when absent or unresolvable. */
    private Integer priority;
    /**
     * WO-C8-36 (CR-01): фаза отправки этого вызова (словарь
     * {@link ServiceTaskDispatchPhase}; nullable — null у старых продюсеров,
     * движок трактует как legacy без проверки). Воркер обязан вернуть В
     * сообщении о завершении без изменений.
     */
    private String dispatchPhase;
    /**
     * WO-C8-36 (CR-01): индекс внутри фазы (позиция слушателя; null для
     * {@code real} и для legacy). Пара (dispatchPhase, dispatchIndex) —
     * идентификатор конкретного вызова.
     */
    private Integer dispatchIndex;
}
