package com.example.dtx.saga.repository;

import com.example.dtx.common.Json;
import com.example.dtx.saga.domain.SagaInstance;
import com.example.dtx.saga.domain.SagaStatus;
import com.example.dtx.saga.domain.StepStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence of saga instances and their step log. Everything the orchestrator
 * needs to resume after a crash lives here.
 */
@Repository
public class SagaRepository {

    private static final RowMapper<SagaInstance> MAPPER = (rs, i) -> new SagaInstance(
            rs.getString("id"),
            rs.getString("biz_id"),
            rs.getString("definition"),
            SagaStatus.valueOf(rs.getString("status")),
            rs.getInt("current_step"),
            rs.getString("payload"),
            rs.getString("error"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public SagaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String id, String bizId, String definition, Map<String, Object> payload) {
        jdbc.update("insert into saga_instance(id, biz_id, definition, status, current_step, payload, "
                        + "created_at, updated_at) values (?, ?, ?, ?, 0, ?, current_timestamp, current_timestamp)",
                id, bizId, definition, SagaStatus.RUNNING.name(), Json.write(payload));
    }

    public Optional<SagaInstance> find(String id) {
        List<SagaInstance> rows = jdbc.query(
                "select * from saga_instance where id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean exists(String id) {
        Integer n = jdbc.queryForObject("select count(*) from saga_instance where id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    public void updateStatus(String id, SagaStatus status, String error) {
        jdbc.update("update saga_instance set status = ?, error = ?, updated_at = current_timestamp where id = ?",
                status.name(), error, id);
    }

    public void updateCurrentStep(String id, int step) {
        jdbc.update("update saga_instance set current_step = ?, updated_at = current_timestamp where id = ?",
                step, id);
    }

    public void markStep(String sagaId, String stepName, int seq, StepStatus status) {
        jdbc.update("merge into saga_step_log(saga_id, step_name, seq, status, updated_at) key (saga_id, step_name) "
                + "values (?, ?, ?, ?, current_timestamp)", sagaId, stepName, seq, status.name());
    }

    public Optional<StepStatus> stepStatus(String sagaId, String stepName) {
        List<String> rows = jdbc.queryForList(
                "select status from saga_step_log where saga_id = ? and step_name = ?",
                String.class, sagaId, stepName);
        return rows.stream().findFirst().map(StepStatus::valueOf);
    }

    /** Executed-but-not-compensated steps, most recent first - the compensation order. */
    public List<String> executedStepsDesc(String sagaId) {
        return jdbc.queryForList(
                "select step_name from saga_step_log where saga_id = ? and status = ? order by seq desc",
                String.class, sagaId, StepStatus.EXECUTED.name());
    }

    /** Non terminal instances: called by the recovery job at startup and periodically. */
    public List<SagaInstance> findUnfinished() {
        return jdbc.query("select * from saga_instance where status in (?, ?, ?) order by updated_at",
                MAPPER, SagaStatus.RUNNING.name(), SagaStatus.COMPENSATING.name(), SagaStatus.FAILED.name());
    }
}
