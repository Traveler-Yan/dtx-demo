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

import java.util.List;
import java.util.Optional;

/**
 * Inventory participant.
 *
 * <p>Try freezes stock, Confirm turns the frozen stock into a real deduction and
 * Cancel releases the freeze. Each method guards the branch state machine so
 * that retries are harmless and a late Try cannot resurrect a cancelled branch.</p>
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final JdbcTemplate jdbc;
    private final TccTransactionRepository tccRepository;

    public InventoryService(JdbcTemplate jdbc, TccTransactionRepository tccRepository) {
        this.jdbc = jdbc;
        this.tccRepository = tccRepository;
    }

    @Transactional
    public void tryDeduct(String xid, String branchId, String sku, int qty) {
        Optional<TccTransaction> existing =
                tccRepository.lockExistingOrCreate(xid, branchId, TccStatus.TRY);
        if (existing.isPresent()) {
            TccStatus status = existing.get().status();
            if (status == TccStatus.TRY || status == TccStatus.CONFIRM) {
                log.info("[{}] repeated Try on {}, ignored (status={})", xid, branchId, status);
                return;
            }
            // CANCEL / EMPTY_ROLLBACK: a delayed Try must never reserve again.
            throw new TccSuspensionException(xid, branchId);
        }

        int updated = jdbc.update(
                "update inventory set available = available - ?, frozen = frozen + ? "
                        + "where sku = ? and available >= ?",
                qty, qty, sku, qty);
        if (updated == 0) {
            throw new BizException("INVENTORY_NOT_ENOUGH",
                    "sku " + sku + " cannot reserve " + qty);
        }
        log.info("[{}] Try frozen {} x {}", xid, qty, sku);
    }

    @Transactional
    public void confirmDeduct(String xid, String branchId, String sku, int qty) {
        Optional<TccTransaction> row = tccRepository.selectForUpdate(xid, branchId);
        if (row.isEmpty()) {
            log.warn("[{}] confirm without a successful Try on {}, nothing to do", xid, branchId);
            return;
        }
        TccStatus status = row.get().status();
        if (status == TccStatus.CONFIRM) {
            log.info("[{}] repeated Confirm on {}, idempotent", xid, branchId);
            return;
        }
        if (status != TccStatus.TRY) {
            throw new BizException("TCC_STATE_CONFLICT",
                    "cannot confirm " + branchId + " in status " + status);
        }

        jdbc.update("update inventory set frozen = frozen - ? where sku = ? and frozen >= ?",
                qty, sku, qty);
        tccRepository.updateStatus(xid, branchId, TccStatus.CONFIRM);
        log.info("[{}] Confirm deducted {} x {}", xid, qty, sku);
    }

    @Transactional
    public void cancelDeduct(String xid, String branchId, String sku, int qty) {
        Optional<TccTransaction> row = tccRepository.selectForUpdate(xid, branchId);
        if (row.isEmpty()) {
            // Cancel overtook Try: remember it, otherwise the late Try would
            // reserve stock that nobody will ever release (empty rollback).
            tccRepository.insert(xid, branchId, TccStatus.EMPTY_ROLLBACK);
            log.warn("[{}] empty rollback on {}: no Try was recorded", xid, branchId);
            return;
        }
        TccStatus status = row.get().status();
        if (status == TccStatus.CANCEL) {
            log.info("[{}] repeated Cancel on {}, idempotent", xid, branchId);
            return;
        }
        if (status.isCancelled()) {
            return;
        }
        if (status == TccStatus.CONFIRM) {
            log.warn("[{}] Cancel after Confirm on {}, ignored", xid, branchId);
            return;
        }

        jdbc.update("update inventory set frozen = frozen - ?, available = available + ? "
                        + "where sku = ? and frozen >= ?",
                qty, qty, sku, qty);
        tccRepository.updateStatus(xid, branchId, TccStatus.CANCEL);
        log.info("[{}] Cancel released {} x {}", xid, qty, sku);
    }

    public InventorySnapshot snapshot(String sku) {
        List<InventorySnapshot> rows = jdbc.query(
                "select sku, available, frozen from inventory where sku = ?",
                (rs, i) -> new InventorySnapshot(rs.getString(1), rs.getInt(2), rs.getInt(3)), sku);
        if (rows.isEmpty()) {
            throw new BizException("SKU_NOT_FOUND", "unknown sku " + sku);
        }
        return rows.get(0);
    }

    public record InventorySnapshot(String sku, int available, int frozen) {
        public int total() {
            return available + frozen;
        }
    }
}
