package com.example.dtx.tcc;

import com.example.dtx.tcc.coordinator.TccBranch;
import com.example.dtx.tcc.coordinator.TccCoordinator;
import com.example.dtx.tcc.coordinator.TccPartialCommitException;
import com.example.dtx.tcc.coordinator.TccResult;
import com.example.dtx.tcc.coordinator.TccSuspensionException;
import com.example.dtx.tcc.domain.Order;
import com.example.dtx.tcc.domain.TccStatus;
import com.example.dtx.tcc.repository.TccTransactionRepository;
import com.example.dtx.tcc.service.AccountService;
import com.example.dtx.tcc.service.InventoryService;
import com.example.dtx.tcc.service.OrderService;
import com.example.dtx.tcc.service.PlaceOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Sql(scripts = {"/schema.sql", "/data.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TccTransactionTest {

    private static final String SKU = "SKU-1001";
    private static final long USER = 1001L;
    private static final String BRANCH = "inventory:" + SKU;

    @Autowired
    private PlaceOrderService placeOrderService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private TccCoordinator coordinator;
    @Autowired
    private TccTransactionRepository tccRepository;

    @Test
    @DisplayName("正常下单：Try 冻结 -> Confirm 扣减，冻结清零")
    void happyPath() {
        TccResult result = placeOrderService.place("ORD-1", USER, SKU, 2, new BigDecimal("100.00"));

        assertThat(result.success()).isTrue();
        assertThat(inventoryService.snapshot(SKU).available()).isEqualTo(98);
        assertThat(inventoryService.snapshot(SKU).frozen()).isZero();
        assertThat(accountService.snapshot(USER).balance()).isEqualByComparingTo("900.00");
        assertThat(accountService.snapshot(USER).frozen()).isEqualByComparingTo("0.00");
        assertThat(orderService.find("ORD-1").orElseThrow().status()).isEqualTo(Order.PAID);

        assertThat(tccRepository.find("TCC:ORD-1", BRANCH).orElseThrow().status())
                .isEqualTo(TccStatus.CONFIRM);
        assertThat(tccRepository.find("TCC:ORD-1", "account").orElseThrow().status())
                .isEqualTo(TccStatus.CONFIRM);
    }

    @Test
    @DisplayName("第二个参与者失败：第一个参与者被 Cancel，库存完全释放")
    void secondParticipantFails_shouldCancelFirst() {
        TccResult result = placeOrderService.place("ORD-2", USER, SKU, 1, new BigDecimal("5000.00"));

        assertThat(result.success()).isFalse();
        assertThat(inventoryService.snapshot(SKU).available()).isEqualTo(100);
        assertThat(inventoryService.snapshot(SKU).frozen()).isZero();
        assertThat(accountService.snapshot(USER).balance()).isEqualByComparingTo("1000.00");
        assertThat(orderService.find("ORD-2").orElseThrow().status()).isEqualTo(Order.CANCELLED);
        assertThat(tccRepository.find("TCC:ORD-2", BRANCH).orElseThrow().status())
                .isEqualTo(TccStatus.CANCEL);
    }

    @Test
    @DisplayName("空回滚：Cancel 先于 Try 到达，记录 EMPTY_ROLLBACK 且不报错")
    void cancelBeforeTry_shouldBeEmptyRollback() {
        String xid = "TCC:ORD-EMPTY";

        inventoryService.cancelDeduct(xid, BRANCH, SKU, 3);

        assertThat(tccRepository.find(xid, BRANCH).orElseThrow().status())
                .isEqualTo(TccStatus.EMPTY_ROLLBACK);
        assertThat(inventoryService.snapshot(SKU).available()).isEqualTo(100);
    }

    @Test
    @DisplayName("悬挂：Cancel 之后迟到的 Try 必须被拒绝，不能预留资源")
    void lateTryAfterCancel_shouldBeRejected() {
        String xid = "TCC:ORD-HANG";
        inventoryService.cancelDeduct(xid, BRANCH, SKU, 3);

        assertThatThrownBy(() -> inventoryService.tryDeduct(xid, BRANCH, SKU, 3))
                .isInstanceOf(TccSuspensionException.class);

        assertThat(inventoryService.snapshot(SKU).available()).isEqualTo(100);
        assertThat(inventoryService.snapshot(SKU).frozen()).isZero();
    }

    @Test
    @DisplayName("幂等：重复 Confirm、Confirm 之后 Cancel 都不改变数据")
    void confirmAndCancelAreIdempotent() {
        placeOrderService.place("ORD-3", USER, SKU, 2, new BigDecimal("100.00"));

        inventoryService.confirmDeduct("TCC:ORD-3", BRANCH, SKU, 2);
        inventoryService.confirmDeduct("TCC:ORD-3", BRANCH, SKU, 2);
        accountService.cancelDebit("TCC:ORD-3", "account", USER, new BigDecimal("100.00"));

        assertThat(inventoryService.snapshot(SKU).available()).isEqualTo(98);
        assertThat(inventoryService.snapshot(SKU).frozen()).isZero();
        assertThat(accountService.snapshot(USER).balance()).isEqualByComparingTo("900.00");
        assertThat(accountService.snapshot(USER).frozen()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("业务重放：同一个订单号提交两次只扣一次")
    void replayedRequestIsIdempotent() {
        placeOrderService.place("ORD-4", USER, SKU, 2, new BigDecimal("100.00"));
        placeOrderService.place("ORD-4", USER, SKU, 2, new BigDecimal("100.00"));

        assertThat(inventoryService.snapshot(SKU).available()).isEqualTo(98);
        assertThat(accountService.snapshot(USER).balance()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("重试：参与者第一次失败、第二次成功，协调器自动重试")
    void transientFailureIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        TccBranch flaky = new TccBranch("flaky",
                () -> {
                    if (attempts.incrementAndGet() < 2) {
                        throw new IllegalStateException("transient network error");
                    }
                },
                () -> {
                },
                () -> {
                });

        TccResult result = coordinator.execute("ORD-FLAKY", List.of(flaky));

        assertThat(result.success()).isTrue();
        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("部分确认失败：抛出 TccPartialCommitException，等待对账修复")
    void partialConfirmRaisesForReconciliation() {
        List<TccBranch> branches = List.of(
                new TccBranch("b1", () -> {
                }, () -> {
                }, () -> {
                }),
                new TccBranch("b2", () -> {
                }, () -> {
                    throw new IllegalStateException("confirm broken");
                }, () -> {
                }));

        assertThatThrownBy(() -> coordinator.execute("ORD-PARTIAL", branches))
                .isInstanceOf(TccPartialCommitException.class)
                .hasMessageContaining("b2");
    }
}
