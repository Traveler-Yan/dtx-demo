package com.example.dtx.message.service;

import com.example.dtx.common.Json;
import com.example.dtx.message.broker.BrokerMessage;
import com.example.dtx.message.repository.IdempotentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Consumer side: grants loyalty points for every created order.
 *
 * <p>The de-duplication row and the point update share one local transaction,
 * so a redelivered message is a no-op and a failed one leaves no trace.</p>
 */
@Service
public class OrderCreatedHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedHandler.class);

    private final JdbcTemplate jdbc;
    private final IdempotentRepository idempotentRepository;

    public OrderCreatedHandler(JdbcTemplate jdbc, IdempotentRepository idempotentRepository) {
        this.jdbc = jdbc;
        this.idempotentRepository = idempotentRepository;
    }

    @Transactional
    public void handle(BrokerMessage message) {
        if (!idempotentRepository.tryAcquire(message.topic(), message.key())) {
            log.info("duplicate delivery of {}:{} ignored", message.topic(), message.key());
            return;
        }

        Map<String, Object> payload = Json.read(message.payload(), Map.class);
        long userId = ((Number) payload.get("userId")).longValue();
        int qty = ((Number) payload.get("qty")).intValue();
        int points = qty * 10;

        int updated = jdbc.update("update points set points = points + ? where user_id = ?", points, userId);
        if (updated == 0) {
            jdbc.update("insert into points(user_id, points) values (?, ?)", userId, points);
        }
        log.info("order {} consumed, {} points granted to user {}", message.key(), points, userId);
    }
}
