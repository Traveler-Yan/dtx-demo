package com.example.dtx.message.relay;

import com.example.dtx.message.broker.MessageBroker;
import com.example.dtx.message.domain.LocalMessage;
import com.example.dtx.message.repository.LocalMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox relay: polls PENDING messages, claims them optimistically, publishes
 * them and records the outcome.
 *
 * <p>Delivery is at-least-once by construction, therefore the consumer must be
 * idempotent.</p>
 */
@Component
public class MessageRelay {

    private static final Logger log = LoggerFactory.getLogger(MessageRelay.class);

    private final LocalMessageRepository repository;
    private final MessageBroker broker;

    @Value("${dtx.message.batch-size:20}")
    private int batchSize;
    @Value("${dtx.message.max-retry:5}")
    private int maxRetry;
    @Value("${dtx.message.retry-backoff-ms:500}")
    private long retryBackoffMs;

    public MessageRelay(LocalMessageRepository repository, MessageBroker broker) {
        this.repository = repository;
        this.broker = broker;
    }

    @Scheduled(fixedDelayString = "${dtx.message.relay-interval-ms:1000}",
            initialDelayString = "${dtx.message.relay-initial-delay-ms:60000}")
    public void schedule() {
        relayOnce();
    }

    /** One relay pass; returns what happened so tests can assert on it. */
    public RelayReport relayOnce() {
        int sent = 0;
        int retried = 0;
        int dead = 0;

        List<LocalMessage> batch = repository.findPending(batchSize);
        for (LocalMessage message : batch) {
            if (!repository.claim(message.id(), message.version())) {
                continue; // another relay won the race
            }
            try {
                broker.send(message.topic(), message.bizKey(), message.payload());
                repository.markSent(message.id());
                sent++;
            } catch (Exception e) {
                int attempts = message.retryCount() + 1;
                if (attempts >= maxRetry) {
                    repository.markDead(message.id());
                    dead++;
                    log.error("message {} moved to DEAD after {} attempts: {}",
                            message.id(), attempts, e.toString());
                } else {
                    long delayNanos = retryBackoffMs <= 0
                            ? -1_000_000_000L
                            : retryBackoffMs * 1_000_000L * attempts;
                    LocalDateTime next = LocalDateTime.now().plusNanos(delayNanos);
                    repository.markRetry(message.id(), attempts, next);
                    retried++;
                    log.warn("message {} delivery failed ({}), retry {}/{}",
                            message.id(), e.toString(), attempts, maxRetry);
                }
            }
        }
        return new RelayReport(sent, retried, dead);
    }

    public record RelayReport(int sent, int retried, int dead) {
    }
}
