package com.example.dtx.saga;

import com.example.dtx.saga.domain.SagaInstance;
import com.example.dtx.saga.domain.SagaStatus;
import com.example.dtx.saga.repository.SagaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Sql(scripts = {"/schema.sql", "/data.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class SagaOrderFlowTest {

    @Autowired
    private SagaOrchestrator orchestrator;
    @Autowired
    private SagaDefinitionRegistry registry;
    @Autowired
    private SagaRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("全部步骤成功：订单 PAID、库存扣减、账户扣款、积分发放")
    void successfulSaga() {
        String sagaId = orchestrator.start(definition(), "ORD-201",
                payload("ORD-201", 3, new BigDecimal("100.00"), false));

        SagaInstance instance = repository.find(sagaId).orElseThrow();
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(orderStatus("ORD-201")).isEqualTo("PAID");
        assertThat(stock()).isEqualTo(47);
        assertThat(balance()).isEqualByComparingTo("400.00");
        assertThat(points()).isEqualTo(30);
    }

    @Test
    @DisplayName("支付失败：反向补偿库存与订单，积分不受影响")
    void paymentFailureCompensatesBackwards() {
        String sagaId = orchestrator.start(definition(), "ORD-202",
                payload("ORD-202", 2, new BigDecimal("100.00"), true));

        SagaInstance instance = repository.find(sagaId).orElseThrow();
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(instance.error()).contains("PAY_GATEWAY_DOWN");
        assertThat(orderStatus("ORD-202")).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(50);
        assertThat(balance()).isEqualByComparingTo("500.00");
        assertThat(points()).isZero();
    }

    @Test
    @DisplayName("库存不足：第一步之外全部回滚，不会留下脏数据")
    void inventoryFailureLeavesNothingBehind() {
        String sagaId = orchestrator.start(definition(), "ORD-203",
                payload("ORD-203", 999, new BigDecimal("10.00"), false));

        assertThat(repository.find(sagaId).orElseThrow().status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(orderStatus("ORD-203")).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(50);
        assertThat(balance()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("重放同一个 bizId 不会创建第二个实例")
    void startIsIdempotent() {
        Map<String, Object> payload = payload("ORD-204", 1, new BigDecimal("10.00"), false);
        String first = orchestrator.start(definition(), "ORD-204", payload);
        String second = orchestrator.start(definition(), "ORD-204", payload);

        assertThat(second).isEqualTo(first);
        Integer instances = jdbc.queryForObject("select count(*) from saga_instance", Integer.class);
        assertThat(instances).isEqualTo(1);
        assertThat(stock()).isEqualTo(49);
    }

    private SagaDefinition definition() {
        return registry.get(SagaDefinitionRegistry.ORDER_SAGA);
    }

    private Map<String, Object> payload(String bizId, int qty, BigDecimal amount, boolean forcePayFailure) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bizId", bizId);
        payload.put("userId", 2001);
        payload.put("sku", "SKU-2001");
        payload.put("qty", qty);
        payload.put("amount", amount);
        payload.put("forcePayFailure", forcePayFailure);
        return payload;
    }

    private String orderStatus(String id) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, id);
    }

    private int stock() {
        Integer value = jdbc.queryForObject("select available from inventory where sku = 'SKU-2001'", Integer.class);
        return value == null ? -1 : value;
    }

    private BigDecimal balance() {
        return jdbc.queryForObject("select balance from account where user_id = 2001", BigDecimal.class);
    }

    private int points() {
        Integer value = jdbc.queryForObject("select points from points where user_id = 2001", Integer.class);
        return value == null ? 0 : value;
    }
}
