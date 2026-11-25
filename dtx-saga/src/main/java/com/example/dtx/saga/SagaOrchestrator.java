package com.example.dtx.saga;

import com.example.dtx.common.Json;
import com.example.dtx.common.RetrySupport;
import com.example.dtx.saga.domain.SagaInstance;
import com.example.dtx.saga.domain.SagaStatus;
import com.example.dtx.saga.domain.StepStatus;
import com.example.dtx.saga.repository.SagaRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SAGA orchestrator: drives the forward path, and on failure compensates the
 * already executed steps in reverse order.
 *
 * <p>Every transition is written to the database before/after the remote call,
 * so a crash at any point leaves enough information for
 * {@link SagaRecoveryJob} to finish the job.</p>
 */
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MILLIS = 20;

    private final SagaRepository repository;

    public SagaOrchestrator(SagaRepository repository) {
        this.repository = repository;
    }

    /** Starts a saga idempotently: replaying the same bizId does not create a second instance. */
    public String start(SagaDefinition definition, String bizId, Map<String, Object> payload) {
        String sagaId = "SAGA:" + bizId;
        if (repository.exists(sagaId)) {
            log.info("[{}] instance already exists, skip start", sagaId);
            return sagaId;
        }
        repository.create(sagaId, bizId, definition.name(), payload);
        forward(sagaId, definition, new SagaContext(sagaId, bizId, payload));
        return sagaId;
    }

    /** Runs the remaining forward steps, compensating everything when one fails. */
    public void forward(String sagaId, SagaDefinition definition, SagaContext context) {
        SagaInstance instance = repository.find(sagaId).orElseThrow();
        List<SagaStep> steps = definition.steps();

        for (int i = instance.currentStep(); i < steps.size(); i++) {
            SagaStep step = steps.get(i);
            try {
                RetrySupport.retry(step.name() + ".execute", MAX_ATTEMPTS, BACKOFF_MILLIS,
                        () -> step.execute(context));
                repository.markStep(sagaId, step.name(), i, StepStatus.EXECUTED);
                repository.updateCurrentStep(sagaId, i + 1);
                log.info("[{}] step {} executed", sagaId, step.name());
            } catch (Exception e) {
                log.warn("[{}] step {} failed, starting compensation: {}", sagaId, step.name(), e.toString());
                compensate(sagaId, definition, context, e.toString());
                return;
            }
        }
        repository.updateStatus(sagaId, SagaStatus.COMPLETED, null);
        log.info("[{}] completed", sagaId);
    }

    /** Compensates executed steps in reverse order. Safe to call repeatedly. */
    public void compensate(String sagaId, SagaDefinition definition, SagaContext context, String error) {
        repository.updateStatus(sagaId, SagaStatus.COMPENSATING, error);

        List<String> failed = new ArrayList<>();
        for (String stepName : repository.executedStepsDesc(sagaId)) {
            SagaStep step = definition.byName(stepName);
            int seq = definition.steps().indexOf(step);
            try {
                RetrySupport.retry(stepName + ".compensate", MAX_ATTEMPTS, BACKOFF_MILLIS,
                        () -> step.compensate(context));
                repository.markStep(sagaId, stepName, seq, StepStatus.COMPENSATED);
                log.info("[{}] step {} compensated", sagaId, stepName);
            } catch (Exception e) {
                log.error("[{}] compensation of {} failed: {}", sagaId, stepName, e.toString());
                failed.add(stepName);
            }
        }

        if (failed.isEmpty()) {
            repository.updateStatus(sagaId, SagaStatus.COMPENSATED, error);
            log.info("[{}] fully compensated", sagaId);
        } else {
            repository.updateStatus(sagaId, SagaStatus.FAILED, "compensation failed: " + failed);
            log.error("[{}] compensation incomplete, needs repair: {}", sagaId, failed);
        }
    }

    /** Rebuilds the context of a persisted instance and continues it. */
    public void resume(SagaInstance instance, SagaDefinition definition) {
        Map<String, Object> payload = Json.read(instance.payload(),
                new TypeReference<Map<String, Object>>() {
                });
        SagaContext context = new SagaContext(instance.id(), instance.bizId(), payload);

        if (instance.status() == SagaStatus.RUNNING) {
            log.info("[{}] resuming forward execution from step {}", instance.id(), instance.currentStep());
            forward(instance.id(), definition, context);
        } else {
            log.info("[{}] resuming compensation (status={})", instance.id(), instance.status());
            compensate(instance.id(), definition, context, instance.error());
        }
    }
}
