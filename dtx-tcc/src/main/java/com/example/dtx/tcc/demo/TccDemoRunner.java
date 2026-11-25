package com.example.dtx.tcc.demo;

import com.example.dtx.tcc.coordinator.TccResult;
import com.example.dtx.tcc.repository.TccTransactionRepository;
import com.example.dtx.tcc.service.AccountService;
import com.example.dtx.tcc.service.InventoryService;
import com.example.dtx.tcc.service.OrderService;
import com.example.dtx.tcc.service.PlaceOrderService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Runs a few TCC scenarios and prints what happened. Enable it with
 * {@code --dtx.demo.enabled=true} (disabled by default so tests stay quiet).
 */
@Component
@ConditionalOnProperty(name = "dtx.demo.enabled", havingValue = "true")
public class TccDemoRunner implements ApplicationRunner {

    private final PlaceOrderService placeOrderService;
    private final InventoryService inventoryService;
    private final AccountService accountService;
    private final OrderService orderService;
    private final TccTransactionRepository tccRepository;

    public TccDemoRunner(PlaceOrderService placeOrderService, InventoryService inventoryService,
                         AccountService accountService, OrderService orderService,
                         TccTransactionRepository tccRepository) {
        this.placeOrderService = placeOrderService;
        this.inventoryService = inventoryService;
        this.accountService = accountService;
        this.orderService = orderService;
        this.tccRepository = tccRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        line("TCC 演示开始");
        report("初始状态");

        TccResult ok = placeOrderService.place("ORD-1", 1001, "SKU-1001", 2, new BigDecimal("100.00"));
        line("场景1 正常下单 -> " + ok);
        report("场景1 之后");

        TccResult fail = placeOrderService.place("ORD-2", 1001, "SKU-1001", 1, new BigDecimal("5000.00"));
        line("场景2 余额不足 -> " + fail);
        report("场景2 之后");

        TccResult noStock = placeOrderService.place("ORD-3", 1001, "SKU-1001", 999, new BigDecimal("1.00"));
        line("场景3 库存不足 -> " + noStock);
        report("场景3 之后");

        line("已确认分支数=" + tccRepository.countByStatus(com.example.dtx.tcc.domain.TccStatus.CONFIRM)
                + ", 已取消分支数=" + tccRepository.countByStatus(com.example.dtx.tcc.domain.TccStatus.CANCEL));

        // CLI-style demo: finish the scenario and stop the JVM.
        System.exit(0);
    }

    private void report(String title) {
        InventoryService.InventorySnapshot inv = inventoryService.snapshot("SKU-1001");
        AccountService.AccountSnapshot acc = accountService.snapshot(1001);
        line(String.format("%-12s 库存[可用=%d 冻结=%d] 账户[余额=%s 冻结=%s] 订单=%s",
                title, inv.available(), inv.frozen(), acc.balance(), acc.frozen(),
                orderService.find("ORD-1").map(o -> o.status()).orElse("-")
                        + "/" + orderService.find("ORD-2").map(o -> o.status()).orElse("-")
                        + "/" + orderService.find("ORD-3").map(o -> o.status()).orElse("-")));
    }

    private void line(String text) {
        System.out.println("[demo] " + text);
    }
}
