package com.example.dtx.saga;

import com.example.dtx.saga.domain.SagaInstance;
import com.example.dtx.saga.repository.SagaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Safety net for crashed orchestrators: any instance left in RUNNING,
 * COMPENSATING or FAILED is resumed. In production this would be a distributed
 * job with a lease, not a local scheduler.
 */
@Component
public class SagaRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryJob.class);

    private final SagaRepository repository;
    private final SagaDefinitionRegistry registry;
    private final SagaOrchestrator orchestrator;

    public SagaRecoveryJob(SagaRepository repository, SagaDefinitionRegistry registry,
                           SagaOrchestrator orchestrator) {
        this.repository = repository;
        this.registry = registry;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${dtx.saga.recovery-interval-ms:60000}",
            initialDelayString = "${dtx.saga.recovery-initial-delay-ms:30000}")
    public void schedule() {
        recoverOnce();
    }

    /** @return number of instances that were retried */
    public int recoverOnce() {
        List<SagaInstance> unfinished = repository.findUnfinished();
        for (SagaInstance instance : unfinished) {
            try {
                orchestrator.resume(instance, registry.get(instance.definition()));
            } catch (Exception e) {
                log.error("[{}] recovery failed: {}", instance.id(), e.toString());
            }
        }
        if (!unfinished.isEmpty()) {
            log.info("recovery pass handled {} unfinished saga instance(s)", unfinished.size());
        }
        return unfinished.size();
    }
}
