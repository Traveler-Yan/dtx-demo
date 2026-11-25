package com.example.dtx.saga.domain;

/** State of one step inside a saga instance. */
public enum StepStatus {
    /** Forward action succeeded. */
    EXECUTED,
    /** Compensation succeeded. */
    COMPENSATED
}
