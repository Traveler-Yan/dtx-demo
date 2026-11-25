package com.example.dtx.tcc.service;

import com.example.dtx.tcc.domain.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Local order storage - the business anchor every TCC attempt is built around. */
@Service
public class OrderService {

    private final JdbcTemplate jdbc;

    public OrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the order if this is the first attempt. Replaying the same
     * business request (same order id) is a no-op - the idempotency key of the
     * whole flow is the order id.
     */
    @Transactional
    public void ensurePending(String id, long userId, String sku, int qty, BigDecimal amount) {
        try {
            jdbc.update("insert into orders(id, user_id, sku, qty, amount, status, created_at) "
                    + "values (?, ?, ?, ?, ?, ?, current_timestamp)",
                    id, userId, sku, qty, amount, Order.PENDING);
        } catch (DuplicateKeyException replay) {
            // Already created by a previous attempt of the same request.
        }
    }

    @Transactional
    public void markPaid(String id) {
        jdbc.update("update orders set status = ? where id = ? and status = ?",
                Order.PAID, id, Order.PENDING);
    }

    @Transactional
    public void markCancelled(String id) {
        jdbc.update("update orders set status = ? where id = ? and status = ?",
                Order.CANCELLED, id, Order.PENDING);
    }

    public Optional<Order> find(String id) {
        List<Order> rows = jdbc.query(
                "select id, user_id, sku, qty, amount, status, created_at from orders where id = ?",
                (rs, i) -> new Order(
                        rs.getString("id"),
                        rs.getLong("user_id"),
                        rs.getString("sku"),
                        rs.getInt("qty"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                id);
        return rows.stream().findFirst();
    }
}
