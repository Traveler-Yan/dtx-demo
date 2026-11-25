package com.example.dtx.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed-delay retry helper. Deliberately tiny: every distributed transaction
 * component needs "retry N times with backoff", and hiding it behind a helper
 * keeps the compensation code readable.
 */
public final class RetrySupport {

    private static final Logger log = LoggerFactory.getLogger(RetrySupport.class);

    private RetrySupport() {
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Runs {@code action} up to {@code attempts} times, rethrowing the last failure. */
    public static void retry(String label, int attempts, long backoffMillis, ThrowingRunnable action) {
        Exception last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                action.run();
                return;
            } catch (Exception e) {
                last = e;
                // Terminal business failures ("stock is gone", "gateway rejected the
                // payment") are not fixed by retrying - fail fast and let the
                // coordinator compensate instead.
                if (e instanceof BizException biz && !biz.isRetryable()) {
                    throw biz;
                }
                log.warn("[retry] {} failed on attempt {}/{}: {}", label, i, attempts, e.toString());
                if (i < attempts) {
                    sleep(backoffMillis);
                }
            }
        }
        throw new BizException("RETRY_EXHAUSTED",
                label + " still failing after " + attempts + " attempts, last error: " + last, true, last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("INTERRUPTED", "retry backoff interrupted", false, e);
        }
    }
}
