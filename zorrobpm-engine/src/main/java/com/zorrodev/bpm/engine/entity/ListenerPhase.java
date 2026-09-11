package com.zorrodev.bpm.engine.entity;

/**
 * WO-AUDIT-4 (A5): listener-phase kind as an enum instead of raw strings — a typo
 * like {@code "doen"} used to compile and leave a hanging phase; now it does not
 * compile. DB values stay lowercase ({@code "start"}/{@code "done"}) via
 * {@link ListenerPhaseConverter}, so no value migration is needed.
 */
public enum ListenerPhase {
    START("start"),
    DONE("done");

    private final String dbValue;

    ListenerPhase(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ListenerPhase fromDb(String dbValue) {
        for (ListenerPhase phase : values()) {
            if (phase.dbValue.equals(dbValue)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unknown listener phase: " + dbValue);
    }
}
