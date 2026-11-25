package com.example.dtx.saga.steps;

import com.example.dtx.saga.SagaContext;
import com.example.dtx.saga.SagaStep;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 5: mark the order as paid. The last step is still compensated when a
 * later step fails, which is why it has its own compensation.
 */
@Component
@Order(5)
public class ConfirmOrderStep implements SagaStep {

    private final JdbcTemplate jdbc;

    public ConfirmOrderStep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "confirmOrder";
    }

    @Override
    @Transactional
    public void execute(SagaContext context) {
        jdbc.update("update orders set status = 'PAID' where id = ? and status = 'CREATED'", context.bizId());
    }

    @Override
    @Transactional
    public void compensate(SagaContext context) {
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", context.bizId());
    }
}
