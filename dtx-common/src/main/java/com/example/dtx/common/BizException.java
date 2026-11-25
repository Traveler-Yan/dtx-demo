package com.example.dtx.common;

/**
 * Business level exception carrying a stable machine readable code.
 *
 * <p>All distributed transaction components fail with this type so that the
 * coordinator can decide whether a failure is retryable or terminal.</p>
 */
public class BizException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public BizException(String code, String message) {
        this(code, message, false, null);
    }

    public BizException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return code + ": " + getMessage();
    }
}
