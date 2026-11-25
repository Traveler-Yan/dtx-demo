package com.example.dtx.message.demo;

import com.example.dtx.message.broker.InMemoryBroker;
import com.example.dtx.message.relay.MessageRelay;
import com.example.dtx.message.service.OrderService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Creates an order, lets the relay publish it, then prints the consumer effect. */
@Component
@ConditionalOnProperty(name = "dtx.demo.enabled", havingValue = "true")
public class MessageDemoRunner implements ApplicationRunner {

    private final OrderService orderService;
    private final MessageRelay relay;
    private final InMemoryBroker broker;
    private final JdbcTemplate jdbc;

    public MessageDemoRunner(OrderService orderService, MessageRelay relay,
                             InMemoryBroker broker, JdbcTemplate jdbc) {
        this.orderService = orderService;
        this.relay = relay;
        this.broker = broker;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        orderService.createOrder("ORD-101", 3001, "SKU-3001", 2, new BigDecimal("20.00"));
        MessageRelay.RelayReport report = relay.relayOnce();

        Integer points = jdbc.queryForObject("select points from points where user_id = 3001", Integer.class);
        System.out.printf("[demo] relay=%s broker消息数=%d 积分=%s%n",
                report, broker.sent().size(), points);

        // CLI-style demo: finish the scenario and stop the JVM (the scheduler would keep it alive).
        System.exit(0);
    }
}
