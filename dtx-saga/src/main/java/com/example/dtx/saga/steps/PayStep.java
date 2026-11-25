package com.example.dtx.saga.steps;

import com.example.dtx.common.BizException;
import com.example.dtx.saga.SagaContext;
import com.example.dtx.saga.SagaStep;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Step 3: charge the account. Set {@code forcePayFailure=true} in the payload to
 * simulate a payment outage and watch the orchestrator compensate backwards.
 */
@Component
@Order(3)
public class PayStep implements SagaStep {

    private final JdbcTemplate jdbc;

    public PayStep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "pay";
    }

    @Override
    @Transactional
    public void execute(SagaContext context) {
        if (context.getBoolean("forcePayFailure")) {
            throw new BizException("PAY_GATEWAY_DOWN", "payment gateway is down");
        }
        BigDecimal amount = context.getDecimal("amount");
        int updated = jdbc.update(
                "update account set balance = balance - ? where user_id = ? and balance >= ?",
                amount, context.getLong("userId"), amount);
        if (updated == 0) {
            throw new BizException("BALANCE_NOT_ENOUGH", "insufficient balance");
        }
    }

    @Override
    @Transactional
    public void compensate(SagaContext context) {
        if (context.getBoolean("forcePayFailure")) {
            return; // execute never debited anything
        }
        jdbc.update("update account set balance = balance + ? where user_id = ?",
                context.getDecimal("amount"), context.getLong("userId"));
    }
}
