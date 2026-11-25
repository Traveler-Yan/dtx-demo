package com.example.dtx.message.domain;

import java.time.LocalDateTime;

/**
 * A row of the outbox table. {@code version} implements optimistic locking so
 * two relay instances never send the same message twice.
 */
public record LocalMessage(String id, String bizType, String bizKey, String topic, String payload,
                           MessageStatus status, int retryCount, int maxRetry,
                           LocalDateTime nextRetryAt, int version, LocalDateTime createdAt) {
}
