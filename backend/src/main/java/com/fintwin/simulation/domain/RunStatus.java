package com.fintwin.simulation.domain;

/** Lifecycle of a simulation run: PENDING (created) -> QUEUED / RUNNING (ML job) -> SUCCEEDED or FAILED. */
public enum RunStatus {
    PENDING, QUEUED, RUNNING, SUCCEEDED, FAILED;

    public boolean active() {
        return this == PENDING || this == QUEUED || this == RUNNING;
    }
}
