package com.example.dtx.message.service;

import com.example.dtx.common.Json;
import com.example.dtx.message.domain.LocalMessage;
import com.example.dtx.message.domain.MessageStatus;
import com.example.dtx.message.domain.Order;
import com.example.dtx.message.repository.LocalMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Producer side of the outbox pattern: the order row and the outbox row are
 * written by the same local transaction, so "order committed" and "message
 * persisted" are the same event.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final JdbcTemplate jdbc;
    private final LocalMessageRepository messageRepository;

    @Value("${dtx.message.max-retry:5}")
    private int maxRetry;

    public OrderService(JdbcTemplate jdbc, LocalMessageRepository messageRepository) {
        this.jdbc = jdbc;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public String createOrder(String orderId, long userId, String sku, int qty, BigDecimal amount) {
        jdbc.update("insert into orders(id, user_id, sku, qty, amount, status, created_at) "
                + "values (?, ?, ?, ?, ?, ?, current_timestamp)",
                orderId, userId, sku, qty, amount, Order.CREATED);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("userId", userId);
        payload.put("sku", sku);
        payload.put("qty", qty);
        payload.put("amount", amount);

        String messageId = UUID.randomUUID().toString();
        messageRepository.insert(new LocalMessage(
                messageId, "ORDER", orderId, "ORDER_CREATED", Json.write(payload),
                MessageStatus.PENDING, 0, maxRetry, LocalDateTime.now(), 0, LocalDateTime.now()));

        log.info("order {} and outbox message {} committed together", orderId, messageId);
        return messageId;
    }
}
