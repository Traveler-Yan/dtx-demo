package com.example.dtx.tcc.domain;

import java.time.LocalDateTime;

/**
 * One row of the TCC transaction control table: the single source of truth that
 * makes Try/Confirm/Cancel safe against network retries and out-of-order calls.
 */
public record TccTransaction(String xid, String branchId, TccStatus status,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
}
