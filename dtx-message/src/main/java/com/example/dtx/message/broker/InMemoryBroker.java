package com.example.dtx.message.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory stand-in for RocketMQ/Kafka.
 *
 * <p>It supports the failure modes that matter for a reliable message flow:
 * broker unavailability ({@link #failNext(int)}) and at-least-once duplicate
 * delivery ({@link #redeliverLast()}).</p>
 */
@Component
@ConditionalOnProperty(name = "dtx.mq.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryBroker implements MessageBroker {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBroker.class);

    private final List<BrokerMessage> sent = new CopyOnWriteArrayList<>();
    private final List<Consumer<BrokerMessage>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();

    @Override
    public void send(String topic, String key, String payload) {
        if (failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new IllegalStateException("broker unavailable");
        }
        BrokerMessage message = new BrokerMessage(topic, key, payload, LocalDateTime.now());
        sent.add(message);
        log.info("broker accepted {}:{}", topic, key);
        dispatch(message);
    }

    private void dispatch(BrokerMessage message) {
        for (Consumer<BrokerMessage> subscriber : subscribers) {
            subscriber.accept(message);
        }
    }

    public void subscribe(Consumer<BrokerMessage> subscriber) {
        subscribers.add(subscriber);
    }

    /** Makes the next {@code times} sends fail, emulating a broker outage. */
    public void failNext(int times) {
        failures.set(times);
    }

    /** Delivers the last accepted message again, emulating at-least-once delivery. */
    public void redeliverLast() {
        if (sent.isEmpty()) {
            throw new IllegalStateException("nothing was sent yet");
        }
        dispatch(sent.get(sent.size() - 1));
    }

    public List<BrokerMessage> sent() {
        return List.copyOf(sent);
    }

    /** Clears recorded traffic but keeps subscribers registered. */
    public void reset() {
        sent.clear();
        failures.set(0);
    }
}
