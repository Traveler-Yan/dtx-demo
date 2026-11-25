package com.example.dtx.message.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Consumer side de-duplication. The row is written in the same local
 * transaction as the business effect, which makes "consume exactly once"
 * collapse into "insert exactly once".
 */
@Repository
public class IdempotentRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true when this callback acquired the key, false when it is a duplicate */
    public boolean tryAcquire(String bizType, String bizKey) {
        try {
            jdbc.update("insert into idempotent_record(biz_type, biz_key, created_at) "
                    + "values (?, ?, current_timestamp)", bizType, bizKey);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
