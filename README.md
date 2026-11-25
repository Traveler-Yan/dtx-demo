# dtx-demo — Java 分布式事务可运行示例集

一套可运行的分布式事务示例，覆盖 TCC、SAGA、可靠消息（本地消息表）三种柔性事务，外加 Seata AT 与
RocketMQ 事务消息的接入代码。全部基于 Spring Boot 3.3 + Java 21。

> 配套手册：`docs/分布式事务详解.pdf`（35 页，10 章 + 2 个附录），与代码逐章对应。

核心模块使用 H2 内存库 + 进程内协调器，**不需要任何中间件**，`mvn test` 即可验证全部语义；
Seata AT 与 RocketMQ 部分需要 `infra/docker-compose.yml` 里的基础设施。

---

## 1. 模块一览

| 模块 | 事务模型 | 是否需要中间件 | 验证方式 |
|---|---|---|---|
| `dtx-common` | 公共组件（重试、JSON、业务异常） | 否 | 编译 |
| `dtx-tcc` | TCC（Try / Confirm / Cancel） | 否 | 8 个用例 |
| `dtx-saga` | SAGA（编排 + 补偿 + 崩溃恢复） | 否 | 8 个用例 |
| `dtx-message` | 本地消息表 + 幂等消费（含 RocketMQ 事务消息代码） | 否（RocketMQ 可选） | 5 个用例 |
| `dtx-seata` | Seata AT（两阶段、undo_log、全局锁） | 是（MySQL + seata-server） | 编译 + 手动运行 |

```
dtx-demo/
├── dtx-common/          重试、JSON、BizException
├── dtx-tcc/             TCC 协调器 + 库存/账户参与者 + 控制表
├── dtx-saga/            SAGA 编排器 + 5 个业务步骤 + 补偿 + 恢复任务
├── dtx-message/         本地消息表 + Relay + 幂等消费者 + RocketMQ 事务消息
├── dtx-seata/           Seata AT 多数据源示例
├── infra/               MySQL / Seata / RocketMQ 的 docker-compose 与初始化脚本
├── docs/                分布式事务详解（Markdown + PDF）
└── tools/pdfgen/        文档转 PDF 的小工具（Flying Saucer + PDFBox）
```

---

## 2. 快速开始

```bash
# 全部编译 + 跑测试（21 个用例，约 30s）
mvn -s .mvn/settings.xml clean test

# 单个模块
mvn -s .mvn/settings.xml -pl dtx-tcc -am test
```

> `.mvn/settings.xml` 把本地仓库指向工作区内的 `.m2repo`，并镜像到阿里云。
> 如果本机 Maven 配置可用，直接 `mvn test` 也可以。

### 跑一个演示（控制台输出业务结果）

```bash
mvn -s .mvn/settings.xml -pl dtx-tcc spring-boot:run -Dspring-boot.run.arguments="--dtx.demo.enabled=true"
mvn -s .mvn/settings.xml -pl dtx-saga spring-boot:run -Dspring-boot.run.arguments="--dtx.demo.enabled=true"
mvn -s .mvn/settings.xml -pl dtx-message spring-boot:run -Dspring-boot.run.arguments="--dtx.demo.enabled=true"
```

演示类都带 `@ConditionalOnProperty(dtx.demo.enabled=true)`，默认不执行，避免污染测试数据。

---

## 3. TCC：三个经典问题是怎么解掉的

`dtx-tcc` 用一个自研的 `TccCoordinator` 扮演 TC 角色，`tcc_transaction(xid, branch_id, status)`
是唯一的事实来源。

| 问题 | 代码位置 | 处理方式 |
|---|---|---|
| 空回滚 | `InventoryService#cancelDeduct` | Cancel 查不到 Try 记录时插入 `EMPTY_ROLLBACK` 并返回成功 |
| 悬挂 | `InventoryService#tryDeduct` | Try 发现状态已是 `CANCEL/EMPTY_ROLLBACK`，抛 `TccSuspensionException` 拒绝预留 |
| 幂等 | 各参与者方法 | 用 `select ... for update` + 状态机判断，重复 Confirm/Cancel 直接返回 |
| 部分确认失败 | `TccCoordinator#execute` | 抛 `TccPartialCommitException`，把失败分支交给对账/人工 |

协调器流程：Try 顺序执行（失败即反向 Cancel）→ 全部成功后 Confirm；`xid = "TCC:" + orderId`，
所以同一笔业务重放天然幂等。

对应测试（`TccTransactionTest`）直接验证：正常下单、第二参与者失败回滚、空回滚、悬挂、
重复 Confirm/Cancel、业务重放、瞬时失败重试、部分确认失败。

---

## 4. SAGA：编排、补偿、崩溃恢复

`dtx-saga` 把下单拆成 5 步：`createOrder → deductInventory → pay → addPoints → confirmOrder`，
每一步都有 `compensate`。`saga_instance` + `saga_step_log` 记录执行进度。

- 失败时按 **反向顺序** 补偿，补偿本身带重试
- 补偿仍失败 → 状态 `FAILED`，由 `SagaRecoveryJob` 下一轮继续
- 进程崩溃后：`SagaRecoveryJob#recoverOnce` 读取 `RUNNING/COMPENSATING/FAILED` 实例继续执行

测试覆盖：全成功、支付失败反向补偿、库存不足、重复提交幂等、补偿顺序、补偿重试、崩溃恢复、
补偿失败转 FAILED。

---

## 5. 可靠消息：本地消息表 + 幂等消费

`dtx-message` 的关键点：

1. **同库同事务**：`orders` 与 `local_message` 在同一个 `@Transactional` 里写入（`OrderService#createOrder`）。
2. **Relay 扫表投递**：`MessageRelay` 先乐观锁抢占（`status=PENDING AND msg_version=?` → `SENDING`），
   再发送，成功 `SENT`，失败按指数退避重试，超过 `max-retry` 进 `DEAD`。
3. **至少一次 + 幂等消费**：`OrderCreatedHandler` 用 `idempotent_record(biz_type, biz_key)` 去重，
   去重行与业务效果在同一个本地事务里。
4. **崩溃自愈**：`OutboxStartupRecovery` 把卡在 `SENDING` 的消息重新入队。

测试覆盖：事务回滚后无孤儿消息、正常投递、失败重试、重复投递幂等、超过重试进死信。

### RocketMQ 事务消息

`RocketMqTransactionalSender`（`dtx.mq.type=rocketmq` 时启用）演示半消息 + 本地事务 + 回查：

```java
producer.sendMessageInTransaction(message, payload);   // 半消息，消费者不可见
// executeLocalTransaction -> COMMIT / ROLLBACK
// checkLocalTransaction  -> 按业务表回查，决定最终状态
```

启动 RocketMQ 后：

```bash
docker compose -f infra/docker-compose.yml up -d namesrv broker
mvn -s .mvn/settings.xml -pl dtx-message spring-boot:run \
  -Dspring-boot.run.arguments="--dtx.mq.type=rocketmq --dtx.rocketmq.namesrv=127.0.0.1:9876"
```

---

## 6. Seata AT

```bash
docker compose -f infra/docker-compose.yml up -d mysql seata
mvn -s .mvn/settings.xml -pl dtx-seata spring-boot:run \
  -Dspring-boot.run.arguments="--dtx.demo.enabled=true"
```

`dtx-seata` 的要点：

- 两个物理库 `dtx_order` / `dtx_stock`，各自包一层 `DataSourceProxy`
- `enable-auto-data-source-proxy: false`，代理手工创建
- 业务方法**不加** `@Transactional`，只加 `@GlobalTransactional`：AT 的一阶段本来就是"每条语句立刻提交 + 写 undo_log"
- 每个库都要有 `undo_log` 表（`infra/mysql/init/*.sql` 已建好）

服务端地址通过 `seata.service.grouplist.default=127.0.0.1:8091`（file 注册中心）指定。

---

## 7. 测试清单

```
dtx-tcc      TccTransactionTest        8 个
dtx-saga     SagaOrderFlowTest         4 个
dtx-saga     SagaEngineTest            4 个
dtx-message  OutboxReliabilityTest     5 个
```

断言的是**数据库里的最终状态**（库存可用/冻结、余额、订单状态、消息状态），而不是日志，
所以能真实反映"事务有没有做对"。

---

## 8. 环境说明

- JDK 21、Maven 3.9+
- H2 2.x（`MODE=MySQL`），每个模块独立的 `jdbc:h2:mem:*` 库，`schema.sql` 每次测试前重建
- `.mvn/settings.xml` 把本地仓库指向工作区内的 `.m2repo`（约 200 MB，可随时删除重建），
  并镜像到阿里云；这样在受限环境下也能构建。若本机 Maven 配置可用，直接 `mvn clean test` 即可
- `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` 固定为 `mock-maker-subclass`：
  受限环境（容器/沙箱禁止 attach agent）下 Mockito 默认的 inline mock maker 会初始化失败，
  subclass 模式无需 agent。本工程测试并未使用 Mockito，此文件仅为兼容性兜底
- 演示应用是**非 Web** 应用：`ApplicationRunner` 执行完调用 `System.exit(0)` 退出，便于脚本化运行

---

## 9. 延伸阅读

`docs/分布式事务详解.pdf`（35 页）系统讲了 2PC/XA、TCC、SAGA、AT、本地消息表、事务消息、
最大努力通知的原理、选型与生产坑位，并逐条对应到本工程的代码。

文档由 Markdown 生成，源文件在 `docs/src/`，重新生成：

```bash
# 先编译 PDF 工具（Flying Saucer + PDFBox）
mvn -s .mvn/settings.xml -f tools/pdfgen/pom.xml package -DskipTests

# Markdown -> XHTML -> PDF（二次排版，自动回填目录页码）
python docs/build_pdf.py
```

工具链说明：Flying Saucer 负责 XHTML+CSS 排版，PDFBox 负责渲染校对图与目录页码解析。
`tools/pdfgen` 是独立工程，不参与主 reactor。
