package com.example.dtx.saga.steps;

import com.example.dtx.saga.SagaContext;
import com.example.dtx.saga.SagaStep;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Step 1: create the order row. Compensation cancels it. */
@Component
@Order(1)
public class CreateOrderStep implements SagaStep {

    private final JdbcTemplate jdbc;

    public CreateOrderStep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "createOrder";
    }

    @Override
    @Transactional
    public void execute(SagaContext context) {
        try {
            jdbc.update("insert into orders(id, user_id, sku, qty, amount, status, created_at) "
                            + "values (?, ?, ?, ?, ?, 'CREATED', current_timestamp)",
                    context.bizId(), context.getLong("userId"), context.getString("sku"),
                    context.getInt("qty"), context.getDecimal("amount"));
        } catch (DuplicateKeyException replay) {
            // idempotent: the same bizId may be replayed
        }
    }

    @Override
    @Transactional
    public void compensate(SagaContext context) {
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", context.bizId());
    }
}
