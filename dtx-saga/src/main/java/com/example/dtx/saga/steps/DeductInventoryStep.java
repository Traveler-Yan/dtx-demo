package com.example.dtx.saga.steps;

import com.example.dtx.common.BizException;
import com.example.dtx.saga.SagaContext;
import com.example.dtx.saga.SagaStep;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Step 2: deduct stock. Compensation gives it back. */
@Component
@Order(2)
public class DeductInventoryStep implements SagaStep {

    private final JdbcTemplate jdbc;

    public DeductInventoryStep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "deductInventory";
    }

    @Override
    @Transactional
    public void execute(SagaContext context) {
        int updated = jdbc.update(
                "update inventory set available = available - ? where sku = ? and available >= ?",
                context.getInt("qty"), context.getString("sku"), context.getInt("qty"));
        if (updated == 0) {
            throw new BizException("INVENTORY_NOT_ENOUGH", "not enough stock for " + context.getString("sku"));
        }
    }

    @Override
    @Transactional
    public void compensate(SagaContext context) {
        jdbc.update("update inventory set available = available + ? where sku = ?",
                context.getInt("qty"), context.getString("sku"));
    }
}
