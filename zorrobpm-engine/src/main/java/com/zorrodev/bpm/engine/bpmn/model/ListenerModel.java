package com.zorrodev.bpm.engine.bpmn.model;

/**
 * A {@code zeebe:executionListener} with {@code eventType="start"} on a service task
 * (WO-C8-11). The listener job blocks dispatch of the real job until it completes.
 * {@code retries} is parsed for completeness (Camunda 8 supports it on listeners) but not
 * consumed yet — listener jobs share the service task's retry budget via the common
 * {@code failServiceTask} path; per-listener budgets are a future WO.
 */
public record ListenerModel(String jobType, Integer retries) {
}
