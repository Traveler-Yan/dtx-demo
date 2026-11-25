package com.example.dtx.message.relay;

import com.example.dtx.message.repository.LocalMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * A relay that died between "claim" and "publish" leaves rows in SENDING;
 * put them back in the queue at startup.
 */
@Component
public class OutboxStartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OutboxStartupRecovery.class);

    private final LocalMessageRepository repository;

    public OutboxStartupRecovery(LocalMessageRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        int requeued = repository.requeueStaleSending(60);
        if (requeued > 0) {
            log.warn("requeued {} message(s) stuck in SENDING after a crash", requeued);
        }
    }
}
