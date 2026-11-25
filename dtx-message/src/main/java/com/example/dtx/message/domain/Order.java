package com.example.dtx.message.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Demo order. */
public record Order(String id, long userId, String sku, int qty, BigDecimal amount,
                    String status, LocalDateTime createdAt) {

    public static final String CREATED = "CREATED";
}
