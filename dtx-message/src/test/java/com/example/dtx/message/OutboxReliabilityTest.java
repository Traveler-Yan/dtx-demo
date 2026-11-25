package com.example.dtx.message;

import com.example.dtx.message.broker.InMemoryBroker;
import com.example.dtx.message.domain.MessageStatus;
import com.example.dtx.message.relay.MessageRelay;
import com.example.dtx.message.repository.LocalMessageRepository;
import com.example.dtx.message.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "dtx.message.retry-backoff-ms=0",
        "dtx.message.max-retry=3"
})
@Sql(scripts = {"/schema.sql", "/data.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class OutboxReliabilityTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private MessageRelay relay;
    @Autowired
    private InMemoryBroker broker;
    @Autowired
    private LocalMessageRepository messageRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetBroker() {
        broker.reset();
    }

    @Test
    @DisplayName("本地事务回滚：订单和消息一起消失，不会出现孤儿消息")
    void orderAndMessageRollBackTogether() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            orderService.createOrder("ORD-TX", 3001, "SKU-3001", 1, new BigDecimal("10.00"));
            throw new IllegalStateException("business rule violated after insert");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("orders")).isZero();
        assertThat(count("local_message")).isZero();
    }

    @Test
    @DisplayName("正常投递：消息 SENT，消费方发放积分")
    void relayPublishesAndConsumerGrantsPoints() {
        String messageId = orderService.createOrder("ORD-1", 3001, "SKU-3001", 2, new BigDecimal("20.00"));

        MessageRelay.RelayReport report = relay.relayOnce();

        assertThat(report.sent()).isEqualTo(1);
        assertThat(broker.sent()).hasSize(1);
        assertThat(messageRepository.find(messageId).orElseThrow().status()).isEqualTo(MessageStatus.SENT);
        assertThat(points()).isEqualTo(20);
    }

    @Test
    @DisplayName("投递失败重试：broker 挂两次后恢复，消息最终 SENT")
    void deliveryIsRetried() {
        String messageId = orderService.createOrder("ORD-2", 3001, "SKU-3001", 1, new BigDecimal("20.00"));
        broker.failNext(2);

        assertThat(relay.relayOnce().retried()).isEqualTo(1);
        assertThat(relay.relayOnce().retried()).isEqualTo(1);
        assertThat(relay.relayOnce().sent()).isEqualTo(1);

        assertThat(messageRepository.find(messageId).orElseThrow().status()).isEqualTo(MessageStatus.SENT);
        assertThat(messageRepository.find(messageId).orElseThrow().retryCount()).isEqualTo(2);
        assertThat(broker.sent()).hasSize(1);
    }

    @Test
    @DisplayName("重复投递：at-least-once 下消费者幂等，积分只加一次")
    void duplicateDeliveryIsIdempotent() {
        orderService.createOrder("ORD-3", 3001, "SKU-3001", 2, new BigDecimal("20.00"));
        relay.relayOnce();

        broker.redeliverLast();
        broker.redeliverLast();

        assertThat(points()).isEqualTo(20);
        assertThat(count("idempotent_record")).isEqualTo(1);
    }

    @Test
    @DisplayName("超过最大重试：进入 DEAD，等待人工处理")
    void exhaustedRetriesGoToDeadLetter() {
        String messageId = orderService.createOrder("ORD-4", 3001, "SKU-3001", 1, new BigDecimal("20.00"));
        broker.failNext(99);

        relay.relayOnce();
        relay.relayOnce();
        relay.relayOnce();

        assertThat(messageRepository.find(messageId).orElseThrow().status()).isEqualTo(MessageStatus.DEAD);
        assertThat(broker.sent()).isEmpty();
        assertThat(points()).isZero();
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private int points() {
        Integer value = jdbc.queryForObject("select points from points where user_id = 3001", Integer.class);
        return value == null ? 0 : value;
    }
}
