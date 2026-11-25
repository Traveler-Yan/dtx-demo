package com.example.dtx.message.repository;

import com.example.dtx.message.domain.LocalMessage;
import com.example.dtx.message.domain.MessageStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Outbox persistence. */
@Repository
public class LocalMessageRepository {

    private static final RowMapper<LocalMessage> MAPPER = (rs, i) -> new LocalMessage(
            rs.getString("id"),
            rs.getString("biz_type"),
            rs.getString("biz_key"),
            rs.getString("topic"),
            rs.getString("payload"),
            MessageStatus.valueOf(rs.getString("status")),
            rs.getInt("retry_count"),
            rs.getInt("max_retry"),
            rs.getTimestamp("next_retry_at").toLocalDateTime(),
            rs.getInt("msg_version"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public LocalMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LocalMessage message) {
        jdbc.update("insert into local_message(id, biz_type, biz_key, topic, payload, status, retry_count, "
                        + "max_retry, next_retry_at, msg_version, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, 0, current_timestamp)",
                message.id(), message.bizType(), message.bizKey(), message.topic(), message.payload(),
                MessageStatus.PENDING.name(), message.retryCount(), message.maxRetry());
    }

    public List<LocalMessage> findPending(int limit) {
        return jdbc.query("select * from local_message where status = ? and next_retry_at <= current_timestamp "
                        + "order by created_at limit " + Math.max(1, limit),
                MAPPER, MessageStatus.PENDING.name());
    }

    /** Optimistic claim: only one relay can flip PENDING -> SENDING for a given version. */
    public boolean claim(String id, int version) {
        int updated = jdbc.update("update local_message set status = ?, msg_version = msg_version + 1 "
                        + "where id = ? and msg_version = ? and status = ?",
                MessageStatus.SENDING.name(), id, version, MessageStatus.PENDING.name());
        return updated == 1;
    }

    public void markSent(String id) {
        jdbc.update("update local_message set status = ? where id = ?", MessageStatus.SENT.name(), id);
    }

    public void markRetry(String id, int retryCount, LocalDateTime nextRetryAt) {
        jdbc.update("update local_message set status = ?, retry_count = ?, next_retry_at = ?, "
                        + "msg_version = msg_version + 1 where id = ?",
                MessageStatus.PENDING.name(), retryCount, Timestamp.valueOf(nextRetryAt), id);
    }

    public void markDead(String id) {
        jdbc.update("update local_message set status = ? where id = ?", MessageStatus.DEAD.name(), id);
    }

    /**
     * Moves messages stuck in SENDING (relay crashed mid-delivery) back to
     * PENDING. Called once at startup; a real system would run it periodically.
     */
    public int requeueStaleSending(long olderThanSeconds) {
        return jdbc.update("update local_message set status = ?, msg_version = msg_version + 1 "
                        + "where status = ? and created_at < dateadd('SECOND', -?, current_timestamp)",
                MessageStatus.PENDING.name(), MessageStatus.SENDING.name(), olderThanSeconds);
    }

    public Optional<LocalMessage> find(String id) {
        List<LocalMessage> rows = jdbc.query("select * from local_message where id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public int countByStatus(MessageStatus status) {
        Integer n = jdbc.queryForObject("select count(*) from local_message where status = ?",
                Integer.class, status.name());
        return n == null ? 0 : n;
    }
}
