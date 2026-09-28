package com.zorrodev.bpm.engine.service;

/**
 * WO-ENG-29: FEEL-выражение io-mapping не вычислилось (неуспех движка —
 * например, «no variable found for name ...» при ссылке source на переменную,
 * которой нет в контексте), а не «честно вернуло null».
 *
 * <p>Наследуется от {@code RuntimeException}, а НЕ от {@code EngineException},
 * осознанно: {@code ActivityServiceImpl.execute()} пробрасывает
 * {@code EngineException} наружу как engine-abort без инцидента, а любой другой
 * {@code Exception} паркует как инцидент через
 * {@code incidentService.raiseIncident(...)}. Ошибка вычисления io-mapping —
 * это именно сбой элемента (оператор чинит данные и резолвит), поэтому она
 * обязана идти incident-путём, а не abort-путём. Легитимный FEEL-null (явный
 * литерал {@code =null}) исключением НЕ является — возвращается Java
 * {@code null} как раньше.
 */
public class FeelEvaluationException extends RuntimeException {

    public FeelEvaluationException(String message) {
        super(message);
    }

    public FeelEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
