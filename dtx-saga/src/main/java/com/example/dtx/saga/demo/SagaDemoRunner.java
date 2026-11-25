package com.example.dtx.saga.demo;

import com.example.dtx.saga.SagaDefinitionRegistry;
import com.example.dtx.saga.SagaOrchestrator;
import com.example.dtx.saga.domain.SagaInstance;
import com.example.dtx.saga.repository.SagaRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Runs one happy path and one compensating path, then prints the final state. */
@Component
@ConditionalOnProperty(name = "dtx.demo.enabled", havingValue = "true")
public class SagaDemoRunner implements ApplicationRunner {

    private final SagaOrchestrator orchestrator;
    private final SagaDefinitionRegistry registry;
    private final SagaRepository repository;
    private final JdbcTemplate jdbc;

    public SagaDemoRunner(SagaOrchestrator orchestrator, SagaDefinitionRegistry registry,
                          SagaRepository repository, JdbcTemplate jdbc) {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Object> ok = payload("ORD-101", 2001, "SKU-2001", 2, new BigDecimal("100.00"), false);
        String okSaga = orchestrator.start(registry.get(SagaDefinitionRegistry.ORDER_SAGA), "ORD-101", ok);
        report("场景1 正常", okSaga);

        Map<String, Object> failing = payload("ORD-102", 2001, "SKU-2001", 3, new BigDecimal("100.00"), true);
        String failSaga = orchestrator.start(registry.get(SagaDefinitionRegistry.ORDER_SAGA), "ORD-102", failing);
        report("场景2 支付失败", failSaga);

        // CLI-style demo: finish the scenario and stop the JVM (the scheduler would keep it alive).
        System.exit(0);
    }

    private Map<String, Object> payload(String bizId, long userId, String sku, int qty,
                                        BigDecimal amount, boolean forcePayFailure) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bizId", bizId);
        payload.put("userId", userId);
        payload.put("sku", sku);
        payload.put("qty", qty);
        payload.put("amount", amount);
        payload.put("forcePayFailure", forcePayFailure);
        return payload;
    }

    private void report(String title, String sagaId) {
        SagaInstance instance = repository.find(sagaId).orElseThrow();
        Integer stock = jdbc.queryForObject("select available from inventory where sku = 'SKU-2001'", Integer.class);
        BigDecimal balance = jdbc.queryForObject("select balance from account where user_id = 2001", BigDecimal.class);
        String orderStatus = jdbc.queryForObject("select status from orders where id = ?", String.class,
                sagaId.replace("SAGA:", ""));
        System.out.printf("[demo] %s saga=%s 状态=%s 库存=%s 余额=%s 订单=%s%n",
                title, sagaId, instance.status(), stock, balance, orderStatus);
    }
}
