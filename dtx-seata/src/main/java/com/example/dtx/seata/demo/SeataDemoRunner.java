package com.example.dtx.seata.demo;

import com.example.dtx.seata.repository.AccountRepository;
import com.example.dtx.seata.repository.OrderRepository;
import com.example.dtx.seata.repository.StockRepository;
import com.example.dtx.seata.service.PlaceOrderService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Two scenarios: a successful order, then one that fails at the account branch
 * and must roll the order and stock branches back through their undo logs.
 */
@Component
@ConditionalOnProperty(name = "dtx.demo.enabled", havingValue = "true")
public class SeataDemoRunner implements ApplicationRunner {

    private final PlaceOrderService placeOrderService;
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final AccountRepository accountRepository;

    public SeataDemoRunner(PlaceOrderService placeOrderService, OrderRepository orderRepository,
                           StockRepository stockRepository, AccountRepository accountRepository) {
        this.placeOrderService = placeOrderService;
        this.orderRepository = orderRepository;
        this.stockRepository = stockRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        placeOrderService.place("AT-OK-1", 1001, "SKU-1", 2, new BigDecimal("100.00"));
        print("场景1 正常", "AT-OK-1");

        try {
            placeOrderService.place("AT-FAIL-1", 1001, "SKU-1", 3, new BigDecimal("999999.00"));
        } catch (Exception e) {
            System.out.println("[demo] 场景2 预期失败: " + e.getMessage());
        }
        print("场景2 回滚后", "AT-FAIL-1");
    }

    private void print(String title, String orderId) {
        System.out.printf("[demo] %s order=%d stock=%d balance=%s%n",
                title, orderRepository.count(orderId), stockRepository.stock("SKU-1"),
                accountRepository.balance(1001));
    }
}
