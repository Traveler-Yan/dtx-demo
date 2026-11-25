package com.example.dtx.tcc.coordinator;

import java.util.List;

/**
 * Outcome of a TCC global transaction.
 *
 * @param unresolvedBranches branches that could neither confirm nor cancel and
 *                           therefore need reconciliation / manual repair
 */
public record TccResult(String xid, boolean success, String message, List<String> unresolvedBranches) {

    public static TccResult ok(String xid) {
        return new TccResult(xid, true, "confirmed", List.of());
    }

    public static TccResult failed(String xid, String message, List<String> unresolvedBranches) {
        return new TccResult(xid, false, message, List.copyOf(unresolvedBranches));
    }
}
