package com.example.dtx.tcc.domain;

/** Lifecycle of one TCC branch, persisted in {@code tcc_transaction}. */
public enum TccStatus {

    /** Try executed, resources are reserved. */
    TRY,
    /** Confirm executed, reservation turned into a real deduction. */
    CONFIRM,
    /** Cancel executed on top of a successful Try. */
    CANCEL,
    /** Cancel arrived before Try: nothing was reserved, but we must remember it. */
    EMPTY_ROLLBACK;

    public boolean isCancelled() {
        return this == CANCEL || this == EMPTY_ROLLBACK;
    }
}
