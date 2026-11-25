package com.example.dtx.saga;

import com.example.dtx.saga.domain.SagaStatus;
import com.example.dtx.saga.domain.StepStatus;
import com.example.dtx.saga.repository.SagaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Sql(scripts = {"/schema.sql", "/data.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class SagaEngineTest {

    @Autowired
    private SagaOrchestrator orchestrator;
    @Autowired
    private SagaRepository repository;
    @Autowired
    private SagaRecoveryJob recoveryJob;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("补偿顺序：严格按相反顺序执行")
    void compensationRunsInReverseOrder() {
        List<String> events = new CopyOnWriteArrayList<>();

        SagaStep s1 = step("s1", events, false);
        SagaStep s2 = step("s2", events, false);
        SagaStep s3 = step("s3", events, true);

        String sagaId = orchestrator.start(
                new SagaDefinition("testSaga", List.of(s1, s2, s3)), "T-REVERSE", Map.of());

        List<String> compensations = events.stream()
                .filter(event -> event.endsWith(".compensate"))
                .toList();
        assertThat(compensations).containsExactly("s2.compensate", "s1.compensate");
        assertThat(repository.find(sagaId).orElseThrow().status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("补偿重试：第一次补偿失败，重试后成功，最终状态为 COMPENSATED")
    void compensationIsRetried() {
        AtomicInteger compensateAttempts = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();

        SagaStep flaky = new SagaStep() {
            @Override
            public String name() {
                return "flaky";
            }

            @Override
            public void execute(SagaContext context) {
                events.add("flaky.execute");
            }

            @Override
            public void compensate(SagaContext context) {
                events.add("flaky.compensate");
                if (compensateAttempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("compensation endpoint timeout");
                }
            }
        };
        SagaStep broken = step("broken", events, true);

        String sagaId = orchestrator.start(
                new SagaDefinition("testSaga", List.of(flaky, broken)), "T-RETRY", Map.of());

        assertThat(compensateAttempts).hasValue(2);
        assertThat(repository.find(sagaId).orElseThrow().status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("崩溃恢复：进程在补偿前挂掉，恢复任务把剩余补偿做完")
    void recoveryCompletesInterruptedCompensation() {
        String sagaId = "SAGA:ORD-301";
        String bizId = "ORD-301";
        Map<String, Object> payload = Map.of(
                "bizId", bizId,
                "userId", 2001,
                "sku", "SKU-2001",
                "qty", 4,
                "amount", "100.00");

        // The business effects that were already applied before the crash.
        jdbc.update("insert into orders(id, user_id, sku, qty, amount, status, created_at) "
                + "values (?, 2001, 'SKU-2001', 4, 100.00, 'CANCELLED', current_timestamp)", bizId);
        jdbc.update("update inventory set available = available - 4 where sku = 'SKU-2001'");

        // The orchestrator died right after persisting COMPENSATING.
        repository.create(sagaId, bizId, SagaDefinitionRegistry.ORDER_SAGA, payload);
        repository.updateStatus(sagaId, SagaStatus.COMPENSATING, "simulated crash");
        repository.markStep(sagaId, "createOrder", 0, StepStatus.EXECUTED);
        repository.markStep(sagaId, "deductInventory", 1, StepStatus.EXECUTED);
        repository.updateCurrentStep(sagaId, 2);

        int recovered = recoveryJob.recoverOnce();

        assertThat(recovered).isEqualTo(1);
        assertThat(repository.find(sagaId).orElseThrow().status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(jdbc.queryForObject("select available from inventory where sku = 'SKU-2001'", Integer.class))
                .isEqualTo(50);
    }

    @Test
    @DisplayName("补偿失败：状态置为 FAILED，等待下一轮恢复")
    void failedCompensationIsMarkedForRepair() {
        List<String> events = new CopyOnWriteArrayList<>();
        SagaStep alwaysFailsToCompensate = new SagaStep() {
            @Override
            public String name() {
                return "stubborn";
            }

            @Override
            public void execute(SagaContext context) {
                events.add("stubborn.execute");
            }

            @Override
            public void compensate(SagaContext context) {
                throw new IllegalStateException("compensation always fails");
            }
        };

        String sagaId = orchestrator.start(
                new SagaDefinition("testSaga", List.of(alwaysFailsToCompensate, step("after", events, true))),
                "T-FAILED", Map.of());

        assertThat(repository.find(sagaId).orElseThrow().status()).isEqualTo(SagaStatus.FAILED);
        assertThat(repository.find(sagaId).orElseThrow().error()).contains("compensation failed");
    }

    private SagaStep step(String name, List<String> events, boolean failOnExecute) {
        return new SagaStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(SagaContext context) {
                events.add(name + ".execute");
                if (failOnExecute) {
                    throw new IllegalStateException(name + " is down");
                }
            }

            @Override
            public void compensate(SagaContext context) {
                events.add(name + ".compensate");
            }
        };
    }
}
