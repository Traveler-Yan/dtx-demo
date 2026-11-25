package com.example.dtx.tcc.coordinator;

import com.example.dtx.common.RetrySupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Minimal in-process TCC coordinator.
 *
 * <p>It plays the role that Seata's TC (or a hand written transaction manager)
 * plays in production: it drives Try for every participant, then Confirm, and
 * when anything fails before the commit point it runs Cancel in reverse order.</p>
 *
 * <p>The {@code xid} is derived from the business id, so replaying the same
 * business request produces the same global transaction id and every phase
 * becomes idempotent.</p>
 */
@Component
public class TccCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TccCoordinator.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MILLIS = 20;

    public TccResult execute(String bizId, List<TccBranch> branches) {
        String xid = "TCC:" + bizId;
        Deque<TccBranch> executed = new ArrayDeque<>();

        try {
            for (TccBranch branch : branches) {
                RetrySupport.retry(branch.name() + ".try", MAX_ATTEMPTS, BACKOFF_MILLIS,
                        () -> branch.tryAction().run());
                executed.push(branch);
            }
        } catch (Exception tryFailure) {
            log.warn("[{}] try phase failed, rolling back: {}", xid, tryFailure.toString());
            List<String> cancelFailed = cancelAll(xid, executed);
            if (!cancelFailed.isEmpty()) {
                throw new TccPartialCommitException(xid, cancelFailed, tryFailure);
            }
            return TccResult.failed(xid, tryFailure.getMessage(), List.of());
        }

        List<String> confirmFailed = new ArrayList<>();
        for (TccBranch branch : branches) {
            try {
                RetrySupport.retry(branch.name() + ".confirm", MAX_ATTEMPTS, BACKOFF_MILLIS,
                        () -> branch.confirmAction().run());
            } catch (Exception e) {
                log.error("[{}] confirm failed for {}, needs reconciliation: {}",
                        xid, branch.name(), e.toString());
                confirmFailed.add(branch.name());
            }
        }
        if (!confirmFailed.isEmpty()) {
            throw new TccPartialCommitException(xid, confirmFailed, null);
        }
        return TccResult.ok(xid);
    }

    private List<String> cancelAll(String xid, Deque<TccBranch> executed) {
        List<String> failed = new ArrayList<>();
        for (TccBranch branch : executed) {
            try {
                RetrySupport.retry(branch.name() + ".cancel", MAX_ATTEMPTS, BACKOFF_MILLIS,
                        () -> branch.cancelAction().run());
                log.info("[{}] cancelled {}", xid, branch.name());
            } catch (Exception e) {
                log.error("[{}] cancel failed for {}: {}", xid, branch.name(), e.toString());
                failed.add(branch.name());
            }
        }
        return failed;
    }
}
