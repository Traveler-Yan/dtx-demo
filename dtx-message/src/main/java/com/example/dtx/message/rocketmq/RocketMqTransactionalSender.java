package com.example.dtx.message.rocketmq;

import com.example.dtx.common.Json;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RocketMQ transactional message variant of the same problem.
 *
 * <p>The half message is invisible to consumers until the local transaction
 * commits. If the broker never learns the outcome it calls
 * {@code checkLocalTransaction} back, which is why the local transaction must
 * be discoverable from the business table alone.</p>
 *
 * <p>Enabled with {@code dtx.mq.type=rocketmq} and a reachable broker; the
 * in-memory outbox demo stays the default so the module runs without infra.</p>
 */
@Component
@ConditionalOnProperty(name = "dtx.mq.type", havingValue = "rocketmq")
public class RocketMqTransactionalSender implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RocketMqTransactionalSender.class);

    public static final String TOPIC = "ORDER_CREATED";

    private final TransactionMQProducer producer;
    private final JdbcTemplate jdbc;

    public RocketMqTransactionalSender(
            JdbcTemplate jdbc,
            @Value("${dtx.rocketmq.namesrv:127.0.0.1:9876}") String namesrv,
            @Value("${dtx.rocketmq.producer-group:dtx_tx_producer}") String group) throws MQClientException {

        this.jdbc = jdbc;
        this.producer = new TransactionMQProducer(group);
        this.producer.setNamesrvAddr(namesrv);
        this.producer.setTransactionListener(new OrderTransactionListener());
        this.producer.start();
        log.info("RocketMQ transactional producer started, namesrv={}", namesrv);
    }

    public TransactionSendResult sendOrderCreated(String orderId, long userId, String sku,
                                                  int qty, BigDecimal amount) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("userId", userId);
        payload.put("sku", sku);
        payload.put("qty", qty);
        payload.put("amount", amount);

        Message message = new Message(TOPIC, "ORDER", orderId,
                Json.write(payload).getBytes(StandardCharsets.UTF_8));
        message.putUserProperty("orderId", orderId);
        return producer.sendMessageInTransaction(message, payload);
    }

    @Override
    public void destroy() {
        producer.shutdown();
    }

    /** Executes the local transaction and tells the broker what happened. */
    private final class OrderTransactionListener implements TransactionListener {

        @Override
        public LocalTransactionState executeLocalTransaction(Message message, Object arg) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) arg;
            try {
                jdbc.update("insert into orders(id, user_id, sku, qty, amount, status, created_at) "
                                + "values (?, ?, ?, ?, ?, 'CREATED', current_timestamp)",
                        payload.get("orderId"),
                        ((Number) payload.get("userId")).longValue(),
                        payload.get("sku"),
                        ((Number) payload.get("qty")).intValue(),
                        new BigDecimal(String.valueOf(payload.get("amount"))));
                return LocalTransactionState.COMMIT_MESSAGE;
            } catch (Exception e) {
                log.error("local transaction failed for {}", payload.get("orderId"), e);
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
        }

        @Override
        public LocalTransactionState checkLocalTransaction(MessageExt message) {
            String orderId = message.getUserProperty("orderId");
            Integer count = jdbc.queryForObject("select count(*) from orders where id = ?", Integer.class, orderId);
            boolean committed = count != null && count > 0;
            log.info("broker asked for {} transaction state: {}", orderId, committed ? "COMMIT" : "ROLLBACK");
            return committed ? LocalTransactionState.COMMIT_MESSAGE : LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }
}
