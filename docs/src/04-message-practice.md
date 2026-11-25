# 第 7 章 可靠消息：把事务变成一条消息

## 7.1 核心矛盾

"本地事务提交"和"消息发送成功"是两个独立动作，任意一个先失败都会导致不一致：

- 先提交再发消息：消息发送失败 → 下游永远不知道
- 先发消息再提交：提交失败 → 下游收到一条不存在的业务

解法只有一个：**让这两个动作共享同一个本地事务**。由此衍生出两种工程实现：本地消息表、
事务消息。

## 7.2 本地消息表（Outbox）

业务表和消息表在**同一个库、同一个事务**里写入，再由后台任务把消息投递出去。

```sql
create table local_message (
    id            varchar(64)   primary key,
    biz_type      varchar(32)   not null,
    biz_key       varchar(64)   not null,
    topic         varchar(128)  not null,
    payload       varchar(4000) not null,
    status        varchar(16)   not null,   -- PENDING/SENDING/SENT/DEAD
    retry_count   int           not null default 0,
    max_retry     int           not null default 5,
    next_retry_at timestamp     not null,
    msg_version   int           not null default 0,
    created_at    timestamp     not null
);
```

```java
@Transactional
public String createOrder(String orderId, long userId, String sku, int qty, BigDecimal amount) {
    jdbc.update("insert into orders(...) values (...)", ...);
    messageRepository.insert(new LocalMessage(
            uuid(), "ORDER", orderId, "ORDER_CREATED", json(payload),
            MessageStatus.PENDING, 0, maxRetry, now(), 0, now()));
    return messageId;
}
```

**投递端（Relay）** 四个关键动作：

1. 扫 `status=PENDING and next_retry_at <= now` 的批次
2. 乐观锁抢占：`update ... set status='SENDING', msg_version=msg_version+1 where id=? and msg_version=? and status='PENDING'`
3. 发送，成功置 `SENT`；失败按指数退避更新 `next_retry_at`
4. 超过 `max_retry` 置 `DEAD`，告警并进人工队列

```java
public RelayReport relayOnce() {
    for (LocalMessage message : repository.findPending(batchSize)) {
        if (!repository.claim(message.id(), message.version())) {
            continue;                       // 另一个 relay 抢到了
        }
        try {
            broker.send(message.topic(), message.bizKey(), message.payload());
            repository.markSent(message.id());
        } catch (Exception e) {
            scheduleRetryOrDeadLetter(message, e);
        }
    }
}
```

**崩溃自愈**：relay 在 `SENDING` 状态崩溃，消息会卡住。启动时把超过 N 秒仍在 `SENDING` 的消息
重新置为 `PENDING` 即可（生产中应定期执行）。

## 7.3 事务消息

以 RocketMQ 为例，流程是：

1. 发送**半消息**（对消费者不可见）
2. Broker 落盘成功后返回，生产者执行本地事务
3. 生产者返回 COMMIT / ROLLBACK / UNKNOWN
4. 若返回 UNKNOWN 或响应丢失，Broker **回查**本地事务状态（默认最多 15 次）
5. COMMIT 后半消息转正，消费者可见

```java
TransactionMQProducer producer = new TransactionMQProducer("order-tx-producer");
producer.setNamesrvAddr("127.0.0.1:9876");
producer.setTransactionListener(new TransactionListener() {

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            orderService.create((OrderDTO) arg);
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (Exception e) {
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        String orderId = msg.getUserProperty("orderId");
        return orderService.exists(orderId)
                ? LocalTransactionState.COMMIT_MESSAGE
                : LocalTransactionState.ROLLBACK_MESSAGE;
    }
});

producer.sendMessageInTransaction(message, orderDTO);
```

回查是这套机制的命门：**本地事务的结果必须能从业务表直接查到**，不能依赖内存状态。回查次数
用尽后消息会被丢弃，因此必须监控回查失败率。

## 7.4 两种方案对比

| 维度 | 本地消息表 | 事务消息 |
|---|---|---|
| 依赖 | 只需要数据库 + 任意 MQ | 需要 MQ 支持事务消息（RocketMQ） |
| 实时性 | 秒级（轮询间隔） | 毫秒级 |
| 侵入性 | 中（建表、写 relay） | 低（生产者侧少量代码） |
| 可靠性 | 高，完全可控 | 高，但受限于 MQ 实现 |
| 消息积压 | 扫描表，可用 CDC 优化 | 无 |
| 适用 | 通用、异构 MQ、需要审计 | 已用 RocketMQ、追求低延迟 |

本地消息表的进阶做法是用 CDC（Canal、Debezium）订阅 binlog 代替轮询，既降低延迟，又避免
对业务表的周期性扫描压力。

## 7.5 消费端幂等

消息系统只保证"至少一次"，重复不可避免。幂等的三种落地方式：

```sql
-- 去重表：与业务效果在同一个本地事务
create table idempotent_record (
    biz_type   varchar(32) not null,
    biz_key    varchar(64) not null,
    created_at timestamp   not null,
    primary key (biz_type, biz_key)
);
```

```java
@Transactional
public void handle(BrokerMessage message) {
    if (!idempotentRepository.tryAcquire(message.topic(), message.key())) {
        return;                                  // 重复消息，直接丢弃
    }
    pointsService.grant(userId, points);
}
```

另外两种方式是**状态机**（只允许 `INIT → PAID`，重复请求因状态不匹配而空转）和 **唯一索引**
（业务表上建业务唯一键，插入冲突即重复）。Redis `SETNX` 只能作为第一道拦截，不能作为唯一保证，
因为 Redis 与数据库不在同一个事务里。

## 7.6 afterCommit 的陷阱

一种常见写法是在事务提交后发消息：

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        mqProducer.send(topic, payload);         // 发送失败？事务已经提交了
    }
});
```

本地事务已经提交，消息发送失败无法回滚，消息就永久丢了。这正是必须用本地消息表或事务消息的
原因：**消息的持久化必须和业务的持久化在同一个事务里**。

## 7.7 最大努力通知与对账

跨企业场景（支付回调、渠道通知）没有共享数据库，只能主动通知并按 1min / 5min / 30min / 1h 阶梯重试，
同时提供查询接口让对方主动来查，最后用日终对账把差异生成工单。最大努力通知允许"最终不一致"，
价值在于把不一致**收敛到可发现、可修复**的状态。

# 第 8 章 工程实践

## 8.1 幂等：一切柔性事务的地基

| 方式 | 实现 | 适用 | 注意 |
|---|---|---|---|
| 去重表 | 唯一键插入 | 通用 | 与业务同事务 |
| 状态机 | 条件更新 | 有明确状态流转 | 状态设计要完备 |
| 唯一索引 | 业务唯一键 | 创建类操作 | 冲突要捕获而非报错 |
| Token | 先取 token 再提交 | 前端防重 | token 要一次性 |
| 版本号 | 乐观锁 | 更新类操作 | 冲突要重试或放弃 |

判断标准：**同一请求执行 1 次和执行 N 次，数据库最终状态必须相同**。

## 8.2 补偿任务的设计

- 有明确的"待补偿"表或状态，而不是靠日志搜索
- 每次补偿有唯一业务键，保证幂等
- 失败记录 `retry_count` 和 `next_retry_at`，指数退避
- 超过阈值进死信表，配告警和工单入口
- 补偿任务本身要能水平扩展（抢占式领取 + 租约）

## 8.3 对账是最后一道防线

再好的分布式事务也会有极低概率的不一致（磁盘损坏、人为误操作、代码 bug）。对账负责发现它们：

1. **准实时对账**：分钟级比对核心流水（订单 vs 支付 vs 库存）
2. **日终全量对账**：T+1 全量比对，处理长尾
3. **差异处理**：自动冲正 + 人工复核，差异必须闭环

对账的输入应该是**双方的流水号**，而不是金额或时间，否则无法精确定位。

## 8.4 超时与重试

| 层 | 建议 |
|---|---|
| 单次 RPC | 200ms ~ 2s，必须设置，禁止无限等待 |
| 全局事务 | 30s ~ 60s，超过则强制回滚或转人工 |
| 补偿重试 | 指数退避，上限 5~10 次 |
| 消息重试 | 分级（秒级、分钟级、小时级），最后进死信 |

重试必须满足两个前提：**被调方幂等**、**重试有上限**。否则会把一次故障放大成雪崩。

## 8.5 可观测性

- **xid 透传**：全局事务 ID 必须打进所有日志、MQ header、HTTP header
- **关键指标**：全局事务成功率、平均耗时、回滚率、补偿积压量、死信数量、对账差异数
- **链路追踪**：SkyWalking / OpenTelemetry 把分布式事务的各个分支串起来
- **告警阈值**：回滚率突增、补偿积压持续增长、TC 不可用

## 8.6 六个常见反模式

1. **在事务里调用第三方 HTTP**：超时不可控，事务被拖死。
2. **用 `@Transactional` 包住 MQ 发送**：消息不在事务里，回滚后消息还在。
3. **补偿逻辑不幂等**：重试两次退两次款。
4. **全局事务范围过大**：把查询、日志、缓存都包进去，锁竞争爆炸。
5. **只做重试不做对账**：重试解决瞬时故障，对账解决"已经错了"。
6. **一个系统混用多种方案**：TCC + SAGA + 消息 + XA 全上，维护成本指数上升。
