package com.example.dtx.saga.steps;

import com.example.dtx.saga.SagaContext;
import com.example.dtx.saga.SagaStep;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Step 4: grant loyalty points. Compensation removes them. */
@Component
@Order(4)
public class AddPointsStep implements SagaStep {

    private final JdbcTemplate jdbc;

    public AddPointsStep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "addPoints";
    }

    @Override
    @Transactional
    public void execute(SagaContext context) {
        int delta = context.getInt("qty") * 10;
        int updated = jdbc.update("update points set points = points + ? where user_id = ?",
                delta, context.getLong("userId"));
        if (updated == 0) {
            jdbc.update("insert into points(user_id, points) values (?, ?)",
                    context.getLong("userId"), delta);
        }
    }

    @Override
    @Transactional
    public void compensate(SagaContext context) {
        jdbc.update("update points set points = points - ? where user_id = ?",
                context.getInt("qty") * 10, context.getLong("userId"));
    }
}
