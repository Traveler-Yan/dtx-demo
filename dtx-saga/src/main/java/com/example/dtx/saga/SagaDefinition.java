package com.example.dtx.saga;

import com.example.dtx.common.BizException;

import java.util.List;

/** Immutable ordered list of steps that makes up one kind of saga. */
public record SagaDefinition(String name, List<SagaStep> steps) {

    public SagaDefinition {
        steps = List.copyOf(steps);
    }

    public SagaStep byName(String stepName) {
        return steps.stream()
                .filter(step -> step.name().equals(stepName))
                .findFirst()
                .orElseThrow(() -> new BizException("SAGA_STEP_UNKNOWN",
                        "no step named " + stepName + " in " + name));
    }
}
