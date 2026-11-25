package com.example.dtx.tcc.coordinator;

import com.example.dtx.common.BizException;

import java.util.List;

/**
 * Phase two partially failed: some branches confirmed and others did not.
 * Data is inconsistent right now and a reconciliation job / manual repair is
 * required. A real implementation persists these branches instead of throwing.
 */
public class TccPartialCommitException extends BizException {

    private final List<String> failedBranches;

    public TccPartialCommitException(String xid, List<String> failedBranches, Throwable cause) {
        super("TCC_PARTIAL_CONFIRM",
                "confirm failed for " + failedBranches + " in " + xid, true, cause);
        this.failedBranches = List.copyOf(failedBranches);
    }

    public List<String> getFailedBranches() {
        return failedBranches;
    }
}
