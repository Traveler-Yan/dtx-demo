package com.example.dtx.saga.domain;

/** State machine of a saga instance. */
public enum SagaStatus {
    /** Forward execution in progress. */
    RUNNING,
    /** A step failed, compensations are being executed. */
    COMPENSATING,
    /** Every step succeeded. */
    COMPLETED,
    /** Every executed step was compensated. */
    COMPENSATED,
    /** Compensation itself failed and needs manual repair / another retry. */
    FAILED;
}
