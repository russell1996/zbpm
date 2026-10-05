package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskCompleteData {
    private UUID serviceTaskId;
    /** "SUCCESS" or "FAILED" — set by the worker/SDK. */
    private String status;
    /** Error text from the worker when {@code status == "FAILED"} (goes into the incident). */
    private String errorMessage;
    private List<ProcessVariable> variables;
    /**
     * WO-OBS-8: W3C traceparent forwarded from the worker's incoming AMQP headers
     * (nullable — null when the worker got no header, e.g. old producer). Duplicated
     * in the AMQP headers by the worker; the engine-side listener prefers headers,
     * falls back to these body fields when an intermediate stripped them.
     */
    private String traceParent;
    /**
     * WO-OBS-8: process instance id forwarded alongside (nullable). Lookup-free
     * MDC on the completion path — no DB read per completion just for logging.
     */
    private String processInstanceId;
    /**
     * WO-C8-36 (CR-01): эхо {@code dispatchPhase} входящего задания (nullable —
     * null у старых воркеров, движок трактует как legacy без проверки).
     */
    private String dispatchPhase;
    /**
     * WO-C8-36 (CR-01): эхо {@code dispatchIndex} входящего задания (nullable).
     */
    private Integer dispatchIndex;
    /**
     * WO-C8-36 (red-team HOLD-1): идентификатор КОНКРЕТНОЙ отправки результата
     * (per-send UUID воркера, стабилен на redelivery из resultCache — та же
     * отправка переигрывается, а не новая). Движок дедуплицирует FAILED-дубликаты
     * ОТКРЫТОЙ фазы по нему (иначе confirm-loss переотправка дважды расходовала
     * бы бюджет одного логического сбоя). Nullable — null у старых воркеров
     * (дедуп невозможен, legacy без проверки).
     */
    private String completionId;
}
