# 第 9 章 选型

## 9.1 决策树

```
这笔业务必须强一致吗？
├─ 是
│   ├─ 事务短、并发低、资源支持 XA ────────► 2PC / XA（Atomikos、Narayana）
│   └─ 并发较高、想少改代码 ──────────────► Seata AT
└─ 否（允许最终一致）
    ├─ 需要资源预留、资金/库存类 ──────────► TCC
    ├─ 长流程、多步骤、天然可补偿 ─────────► SAGA
    ├─ 异步解耦、已有 MQ ─────────────────► 事务消息 / 本地消息表
    └─ 跨企业通知 ────────────────────────► 最大努力通知 + 对账
```

## 9.2 场景对照

| 场景 | 推荐方案 | 理由 |
|---|---|---|
| 银行核心转账 | XA / 强一致 TCC | 一致性优先，事务短 |
| 电商下单扣库存 | Seata AT 或 TCC | 需要准实时一致，库存可预留 |
| 出行订单（机票+酒店） | SAGA | 长流程，步骤可取消 |
| 下单后发积分/发券 | 本地消息表 / 事务消息 | 最终一致，允许延迟 |
| 支付回调通知 | 最大努力通知 + 对账 | 跨企业，无共享库 |
| 跨库报表写入 | 消息 / 离线对账 | 不要求实时一致 |
| 分库分表跨片事务 | ShardingSphere XA 或柔性事务 | 视一致性要求 |

## 9.3 组合使用

一套系统里同时存在多种事务模型是正常的，但要**按业务分层**，不要在同一笔业务里叠加：

- 核心账务：TCC（强预留）
- 交易主链路：Seata AT（低侵入）
- 下游通知：可靠消息（最终一致）
- 日终：对账（兜底）

## 9.4 五个判断依据

1. **不一致的业务代价**：钱 > 库存 > 积分 > 通知
2. **事务时长**：毫秒级可用 XA/AT，秒级以上优先 SAGA/消息
3. **并发量**：高并发下全局锁和 XA 连接占用是瓶颈
4. **团队能力**：TCC 要求团队能正确处理三大问题，否则不如 AT
5. **可运维性**：有没有 TC 的高可用、监控、对账、人工修复通道

## 9.5 落地路线

引入分布式事务不要一步到位，下面这个顺序风险最低：

1. **先收敛边界**：能合并的服务合并，能同库的挪到同库，把真正需要分布式事务的地方压到最少。
2. **定一致性等级**：逐条业务问"不一致的代价是什么"，分成强一致、准强一致、最终一致三档。
3. **选最小方案**：能用消息就不上 TCC，能用 AT 就不写 XA，先跑通一条主链路再推广。
4. **补齐兜底**：幂等、重试、死信、对账、告警，这五件事必须在灰度前就绪。
5. **灰度与压测**：按流量比例灰度，重点盯回滚率、补偿积压、协调者负载三个指标。

一条经验：**分布式事务的复杂度应该与业务价值成正比**。给积分发放上一套 TCC，是把工程资源花在了
错误的地方；而资金链路省掉对账，是把风险留给了财务。

# 第 10 章 配套示例工程

## 10.1 结构

`dtx-demo` 是一套可直接运行的 Maven 多模块工程，与本书章节一一对应：

| 模块 | 对应章节 | 内容 | 依赖中间件 |
|---|---|---|---|
| dtx-common | — | 重试、JSON、业务异常 | 无 |
| dtx-tcc | 第 4 章 | TCC 协调器、库存/账户参与者、控制表 | 无（H2） |
| dtx-saga | 第 5 章 | 编排器、5 个步骤、补偿、恢复任务 | 无（H2） |
| dtx-message | 第 7 章 | 本地消息表、Relay、幂等消费者、RocketMQ 事务消息 | 无（RocketMQ 可选） |
| dtx-seata | 第 6 章 | 双数据源 AT 示例 | MySQL + seata-server |

## 10.2 运行

```bash
# 全部测试（21 个用例）
mvn clean test

# 运行演示
java -jar dtx-tcc/target/dtx-tcc-1.0.0.jar --dtx.demo.enabled=true
java -jar dtx-saga/target/dtx-saga-1.0.0.jar --dtx.demo.enabled=true
java -jar dtx-message/target/dtx-message-1.0.0.jar --dtx.demo.enabled=true
```

TCC 演示的输出（真实运行结果）：

```
[demo] 初始状态    库存[可用=100 冻结=0] 账户[余额=1000.00 冻结=0.00]
[demo] 场景1 正常下单 -> success=true
[demo] 场景1 之后   库存[可用=98 冻结=0] 账户[余额=900.00 冻结=0.00] 订单=PAID
[demo] 场景2 余额不足 -> success=false
[demo] 场景2 之后   库存[可用=98 冻结=0] 账户[余额=900.00 冻结=0.00] 订单=CANCELLED
[demo] 场景3 库存不足 -> success=false
[demo] 场景3 之后   库存[可用=98 冻结=0] 账户[余额=900.00 冻结=0.00] 订单=CANCELLED
```

SAGA 演示：正常流程 `COMPLETED`，支付失败流程 `COMPENSATED`，库存与余额都回到原值。

## 10.3 测试证明了什么

| 测试类 | 用例 | 证明的结论 |
|---|---|---|
| TccTransactionTest | 8 | Try/Confirm/Cancel 正确；空回滚、悬挂、幂等都有防护；重放不重复扣减 |
| SagaOrderFlowTest | 4 | 全成功、支付失败反向补偿、库存不足、重放幂等 |
| SagaEngineTest | 4 | 补偿顺序、补偿重试、崩溃恢复、补偿失败转 FAILED |
| OutboxReliabilityTest | 5 | 事务原子性、投递重试、重复投递幂等、死信 |

断言全部落在**数据库最终状态**（可用/冻结库存、余额、订单状态、消息状态），而不是日志文本。

## 10.4 关键实现索引

- 空回滚：`dtx-tcc/.../InventoryService#cancelDeduct`
- 悬挂防护：`dtx-tcc/.../InventoryService#tryDeduct`
- TCC 协调：`dtx-tcc/.../TccCoordinator#execute`
- 反向补偿：`dtx-saga/.../SagaOrchestrator#compensate`
- 崩溃恢复：`dtx-saga/.../SagaRecoveryJob#recoverOnce`
- 消息抢占：`dtx-message/.../LocalMessageRepository#claim`
- 幂等消费：`dtx-message/.../OrderCreatedHandler#handle`

## 10.5 与真实中间件的差距

示例工程刻意把"协调者"和"消息中间件"做成进程内实现，以便脱离基础设施运行。生产化时需要替换：

| 示例中的实现 | 生产替换 |
|---|---|
| `TccCoordinator` | Seata TC / 自研事务管理器 + 注册中心 |
| `InMemoryBroker` | RocketMQ / Kafka |
| `MessageRelay` 轮询 | CDC（Canal、Debezium）或事务消息 |
| `SagaRecoveryJob` 本地调度 | 分布式任务调度（XXL-Job、ElasticJob）+ 租约 |
| H2 | MySQL / PostgreSQL，并补齐 undo_log、索引、分区 |
