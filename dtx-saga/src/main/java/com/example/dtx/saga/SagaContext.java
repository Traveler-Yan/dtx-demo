package com.example.dtx.saga;

import com.example.dtx.common.BizException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Data shared by every step of one saga instance. */
public class SagaContext {

    private final String sagaId;
    private final String bizId;
    private final Map<String, Object> payload;

    public SagaContext(String sagaId, String bizId, Map<String, Object> payload) {
        this.sagaId = sagaId;
        this.bizId = bizId;
        this.payload = payload == null ? new HashMap<>() : new HashMap<>(payload);
    }

    public String sagaId() {
        return sagaId;
    }

    public String bizId() {
        return bizId;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public String getString(String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public long getLong(String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    public int getInt(String key) {
        return (int) getLong(key);
    }

    public boolean getBoolean(String key) {
        Object value = payload.get(key);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    public BigDecimal getDecimal(String key) {
        Object value = payload.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null) {
            throw new BizException("SAGA_PAYLOAD_MISSING", "missing payload key " + key);
        }
        return new BigDecimal(String.valueOf(value));
    }
}
