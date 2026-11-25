package com.example.dtx.seata.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;

/**
 * Order storage. Note there is no {@code @Transactional} here: under AT every
 * statement is its own local transaction that commits immediately, and Seata's
 * undo log compensates it if the global transaction rolls back.
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(@Qualifier("orderDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void insert(String id, long userId, String sku, int qty, BigDecimal amount) {
        jdbc.update("insert into orders(id, user_id, sku, qty, amount, status, created_at) "
                        + "values (?, ?, ?, ?, ?, 'CREATED', now())",
                id, userId, sku, qty, amount);
    }

    public String status(String id) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, id);
    }

    public int count(String id) {
        Integer n = jdbc.queryForObject("select count(*) from orders where id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }
}
