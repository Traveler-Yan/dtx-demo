package com.example.dtx.tcc.repository;

import com.example.dtx.common.BizException;
import com.example.dtx.tcc.domain.TccStatus;
import com.example.dtx.tcc.domain.TccTransaction;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Persistence for the TCC transaction control table. */
@Repository
public class TccTransactionRepository {

    private static final RowMapper<TccTransaction> MAPPER = (rs, rowNum) -> new TccTransaction(
            rs.getString("xid"),
            rs.getString("branch_id"),
            TccStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public TccTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reads the branch row and locks it for the duration of the caller's
     * transaction. Locking is what serialises concurrent Try/Cancel calls for
     * the same branch, which is exactly the race we need to survive.
     */
    public Optional<TccTransaction> selectForUpdate(String xid, String branchId) {
        List<TccTransaction> rows = jdbc.query(
                "select xid, branch_id, status, created_at, updated_at "
                        + "from tcc_transaction where xid = ? and branch_id = ? for update",
                MAPPER, xid, branchId);
        return rows.stream().findFirst();
    }

    public void insert(String xid, String branchId, TccStatus status) {
        jdbc.update("insert into tcc_transaction(xid, branch_id, status, created_at, updated_at) "
                + "values (?, ?, ?, current_timestamp, current_timestamp)", xid, branchId, status.name());
    }

    public void updateStatus(String xid, String branchId, TccStatus status) {
        jdbc.update("update tcc_transaction set status = ?, updated_at = current_timestamp "
                + "where xid = ? and branch_id = ?", status.name(), xid, branchId);
    }

    public int countByStatus(TccStatus status) {
        Integer n = jdbc.queryForObject(
                "select count(*) from tcc_transaction where status = ?", Integer.class, status.name());
        return n == null ? 0 : n;
    }

    public Optional<TccTransaction> find(String xid, String branchId) {
        List<TccTransaction> rows = jdbc.query(
                "select xid, branch_id, status, created_at, updated_at "
                        + "from tcc_transaction where xid = ? and branch_id = ?",
                MAPPER, xid, branchId);
        return rows.stream().findFirst();
    }

    /**
     * Locks the branch row, creating it with {@code initial} status when absent.
     *
     * @return the existing row when the branch was already known, or
     *         {@link Optional#empty()} when this call created it
     */
    public Optional<TccTransaction> lockExistingOrCreate(String xid, String branchId, TccStatus initial) {
        Optional<TccTransaction> existing = selectForUpdate(xid, branchId);
        if (existing.isPresent()) {
            return existing;
        }
        try {
            insert(xid, branchId, initial);
        } catch (DuplicateKeyException race) {
            return selectForUpdate(xid, branchId);
        }
        return Optional.empty();
    }
}
