package com.example.dtx.seata.service;

import com.example.dtx.seata.repository.AccountRepository;
import com.example.dtx.seata.repository.OrderRepository;
import com.example.dtx.seata.repository.StockRepository;
import io.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * The whole use case runs inside one global transaction.
 *
 * <p>Phase one: every statement commits locally and records an undo log.
 * Phase two: the TC tells each branch to commit (drop the undo log) or to roll
 * back (replay the before image).</p>
 */
@Service
public class PlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final AccountRepository accountRepository;

    public PlaceOrderService(OrderRepository orderRepository, StockRepository stockRepository,
                             AccountRepository accountRepository) {
        this.orderRepository = orderRepository;
        this.stockRepository = stockRepository;
        this.accountRepository = accountRepository;
    }

    @GlobalTransactional(name = "place-order", rollbackFor = Exception.class)
    public void place(String orderId, long userId, String sku, int qty, BigDecimal amount) {
        orderRepository.insert(orderId, userId, sku, qty, amount);
        stockRepository.deduct(sku, qty);
        accountRepository.debit(userId, amount);
        log.info("global transaction committed for {}", orderId);
    }
}
