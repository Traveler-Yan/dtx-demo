package com.example.dtx.message.consumer;

import com.example.dtx.message.broker.InMemoryBroker;
import com.example.dtx.message.broker.MessageBroker;
import com.example.dtx.message.service.OrderCreatedHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Wires the consumer to the in-memory broker. With a real broker the same
 * handler would be registered on a {@code DefaultMQPushConsumer}.
 *
 * <p>Registration happens in {@code @PostConstruct} (during context refresh),
 * i.e. before any {@code ApplicationRunner} produces messages.</p>
 */
@Component
public class ConsumerRegistrar {

    private final MessageBroker broker;
    private final OrderCreatedHandler handler;

    public ConsumerRegistrar(MessageBroker broker, OrderCreatedHandler handler) {
        this.broker = broker;
        this.handler = handler;
    }

    @PostConstruct
    public void subscribe() {
        if (broker instanceof InMemoryBroker inMemory) {
            inMemory.subscribe(handler::handle);
        }
    }
}
