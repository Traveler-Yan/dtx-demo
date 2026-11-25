package com.example.dtx.tcc.coordinator;

/**
 * One participant of a TCC transaction.
 *
 * <p>The three actions must be <strong>idempotent</strong>: the coordinator is
 * allowed to call any of them again after a timeout.</p>
 */
public record TccBranch(String name,
                        TccAction tryAction,
                        TccAction confirmAction,
                        TccAction cancelAction) {
}
