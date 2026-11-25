package com.example.dtx.saga.domain;

import java.time.LocalDateTime;

/** Persisted saga instance, the anchor that makes crash recovery possible. */
public record SagaInstance(String id, String bizId, String definition, SagaStatus status,
                           int currentStep, String payload, String error,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
}
