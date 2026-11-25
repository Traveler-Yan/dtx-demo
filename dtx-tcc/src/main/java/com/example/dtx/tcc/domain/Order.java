package com.example.dtx.tcc.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Business anchor record of the demo: one order attempt. */
public record Order(String id, long userId, String sku, int qty, BigDecimal amount,
                    String status, LocalDateTime createdAt) {

    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";
}
