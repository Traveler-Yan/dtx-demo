package com.example.dtx.tcc.coordinator;

import com.example.dtx.common.BizException;

/**
 * Thrown by Try when a Cancel (or an empty rollback) already happened for the
 * same branch. Executing Try now would reserve resources that nobody will ever
 * release again - the classic "suspension" (悬挂) problem.
 */
public class TccSuspensionException extends BizException {

    public TccSuspensionException(String xid, String branchId) {
        super("TCC_SUSPENDED",
                "branch " + branchId + " of " + xid + " was already cancelled, Try rejected to avoid suspension");
    }
}
