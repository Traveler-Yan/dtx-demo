package com.example.dtx.tcc.service;

import com.example.dtx.common.BizException;
import com.example.dtx.tcc.coordinator.TccSuspensionException;
import com.example.dtx.tcc.domain.TccStatus;
import com.example.dtx.tcc.domain.TccTransaction;
import com.example.dtx.tcc.repository.TccTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Account participant: freezes money on Try, deducts it on Confirm, releases it on Cancel. */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final JdbcTemplate jdbc;
    private final TccTransactionRepository tccRepository;

    public AccountService(JdbcTemplate jdbc, TccTransactionRepository tccRepository) {
        this.jdbc = jdbc;
        this.tccRepository = tccRepository;
    }

    @Transactional
    public void tryDebit(String xid, String branchId, long userId, BigDecimal amount) {
        Optional<TccTransaction> existing =
                tccRepository.lockExistingOrCreate(xid, branchId, TccStatus.TRY);
        if (existing.isPresent()) {
            TccStatus status = existing.get().status();
            if (status == TccStatus.TRY || status == TccStatus.CONFIRM) {
                log.info("[{}] repeated Try on {}, ignored (status={})", xid, branchId, status);
                return;
            }
            throw new TccSuspensionException(xid, branchId);
        }

        int updated = jdbc.update(
                "update account set balance = balance - ?, frozen = frozen + ? "
                        + "where user_id = ? and balance >= ?",
                amount, amount, userId, amount);
        if (updated == 0) {
            throw new BizException("BALANCE_NOT_ENOUGH",
                    "user " + userId + " cannot reserve " + amount);
        }
        log.info("[{}] Try froze {} for user {}", xid, amount, userId);
    }

    @Transactional
    public void confirmDebit(String xid, String branchId, long userId, BigDecimal amount) {
        Optional<TccTransaction> row = tccRepository.selectForUpdate(xid, branchId);
        if (row.isEmpty()) {
            log.warn("[{}] confirm without a successful Try on {}, nothing to do", xid, branchId);
            return;
        }
        if (row.get().status() == TccStatus.CONFIRM) {
            log.info("[{}] repeated Confirm on {}, idempotent", xid, branchId);
            return;
        }
        if (row.get().status() != TccStatus.TRY) {
            throw new BizException("TCC_STATE_CONFLICT",
                    "cannot confirm " + branchId + " in status " + row.get().status());
        }

        jdbc.update("update account set frozen = frozen - ? where user_id = ? and frozen >= ?",
                amount, userId, amount);
        tccRepository.updateStatus(xid, branchId, TccStatus.CONFIRM);
        log.info("[{}] Confirm debited {} from user {}", xid, amount, userId);
    }

    @Transactional
    public void cancelDebit(String xid, String branchId, long userId, BigDecimal amount) {
        Optional<TccTransaction> row = tccRepository.selectForUpdate(xid, branchId);
        if (row.isEmpty()) {
            tccRepository.insert(xid, branchId, TccStatus.EMPTY_ROLLBACK);
            log.warn("[{}] empty rollback on {}: no Try was recorded", xid, branchId);
            return;
        }
        TccStatus status = row.get().status();
        if (status.isCancelled() || status == TccStatus.CONFIRM) {
            return;
        }

        jdbc.update("update account set frozen = frozen - ?, balance = balance + ? "
                        + "where user_id = ? and frozen >= ?",
                amount, amount, userId, amount);
        tccRepository.updateStatus(xid, branchId, TccStatus.CANCEL);
        log.info("[{}] Cancel released {} for user {}", xid, amount, userId);
    }

    public AccountSnapshot snapshot(long userId) {
        List<AccountSnapshot> rows = jdbc.query(
                "select user_id, balance, frozen from account where user_id = ?",
                (rs, i) -> new AccountSnapshot(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                userId);
        if (rows.isEmpty()) {
            throw new BizException("ACCOUNT_NOT_FOUND", "unknown user " + userId);
        }
        return rows.get(0);
    }

    public record AccountSnapshot(long userId, BigDecimal balance, BigDecimal frozen) {
    }
}
