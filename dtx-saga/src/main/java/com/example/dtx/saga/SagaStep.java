package com.example.dtx.saga;

/**
 * One step of a saga.
 *
 * <p>Both methods must be idempotent: the orchestrator retries them and may
 * replay them after a crash. {@code compensate} must also tolerate the case
 * where {@code execute} never produced any effect.</p>
 */
public interface SagaStep {

    String name();

    void execute(SagaContext context) throws Exception;

    void compensate(SagaContext context) throws Exception;
}
