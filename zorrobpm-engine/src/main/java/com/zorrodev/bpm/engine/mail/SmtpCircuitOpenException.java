package com.zorrodev.bpm.engine.mail;

/**
 * WO-REL-33 п.2: цепь разомкнута — вызов во внешнюю систему не выполнялся.
 * Отличается от сбоя самого SMTP: это отказоустойчивый отказ без похода в сеть
 * (не занимает scheduler-поток 10-секундным таймаутом).
 */
public class SmtpCircuitOpenException extends RuntimeException {

    public SmtpCircuitOpenException(String message) {
        super(message);
    }
}
