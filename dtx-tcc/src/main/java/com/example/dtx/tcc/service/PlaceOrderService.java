package com.example.dtx.tcc.service;

import com.example.dtx.tcc.coordinator.TccBranch;
import com.example.dtx.tcc.coordinator.TccCoordinator;
import com.example.dtx.tcc.coordinator.TccResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Transaction manager (TM) role: wires the business use case to the TCC
 * coordinator. The order row is the business anchor; inventory and account are
 * the two TCC participants.
 */
@Service
public class PlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final AccountService accountService;
    private final TccCoordinator coordinator;

    public PlaceOrderService(OrderService orderService,
                             InventoryService inventoryService,
                             AccountService accountService,
                             TccCoordinator coordinator) {
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.accountService = accountService;
        this.coordinator = coordinator;
    }

    public TccResult place(String orderId, long userId, String sku, int qty, BigDecimal amount) {
        orderService.ensurePending(orderId, userId, sku, qty, amount);

        String xid = "TCC:" + orderId;
        String inventoryBranch = "inventory:" + sku;

        List<TccBranch> branches = List.of(
                new TccBranch(inventoryBranch,
                        () -> inventoryService.tryDeduct(xid, inventoryBranch, sku, qty),
                        () -> inventoryService.confirmDeduct(xid, inventoryBranch, sku, qty),
                        () -> inventoryService.cancelDeduct(xid, inventoryBranch, sku, qty)),
                new TccBranch("account",
                        () -> accountService.tryDebit(xid, "account", userId, amount),
                        () -> accountService.confirmDebit(xid, "account", userId, amount),
                        () -> accountService.cancelDebit(xid, "account", userId, amount)));

        try {
            TccResult result = coordinator.execute(orderId, branches);
            if (result.success()) {
                orderService.markPaid(orderId);
            } else {
                orderService.markCancelled(orderId);
            }
            log.info("[{}] place order finished: success={}, {}", xid, result.success(), result.message());
            return result;
        } catch (RuntimeException e) {
            // Phase two broke: the order must not stay PENDING.
            orderService.markCancelled(orderId);
            throw e;
        }
    }
}
