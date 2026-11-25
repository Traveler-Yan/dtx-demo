# 第 5 章 SAGA：用补偿换可用性

## 5.1 模型

SAGA 把一个长事务拆成 N 个本地事务 T1…Tn，每个 Ti 配一个补偿操作 Ci：

- 正常：T1 → T2 → … → Tn，全部成功则结束
- 失败：第 k 步失败，反向执行 Ck-1 → … → C1

SAGA 不预留资源、不加锁，所以性能最好；代价是**没有隔离性**，中间状态对外可见，且补偿逻辑
必须由业务保证正确。

## 5.2 编排与协同

**编排（Orchestration）**：有一个中心协调器，按状态机决定下一步调用谁。

- 优点：流程集中可见、易调试、易做超时与补偿
- 缺点：协调器是单点，需要高可用

**协同（Choreography）**：服务之间通过事件驱动，没有中心。

- 优点：去中心、松耦合
- 缺点：流程散落在各处，出问题很难定位，循环依赖风险高

生产上 **优先编排**。Seata Saga、Temporal、AWS Step Functions 都是编排模型。

## 5.3 补偿设计的五条原则

1. **补偿必须幂等**：可能被重试多次。
2. **补偿必须可重入**：从任意中断点继续执行都能收敛。
3. **允许空补偿**：正向操作没执行过时，补偿要能安全返回。
4. **补偿不能失败到底**：失败要有重试上限 + 死信 + 人工工单。
5. **补偿顺序严格反向**：T1 依赖 T2 的结果时，必须先补偿 T2 再补偿 T1。

## 5.4 没有隔离性怎么办

SAGA 的中间态（订单已创建、库存已扣、还没付款）对其他事务可见。四种常见对策：

**语义锁。** 给业务记录加状态字段，用状态表达"进行中"。例如订单 `status=CREATED` 时不允许
发货，必须 `PAID`。

**版本号 / 条件更新。** 补偿和正向操作都带前置状态条件：

```sql
update orders set status = 'PAID' where id = ? and status = 'CREATED';
```

更新 0 行说明状态已被别人改过，直接放弃，避免覆盖。

**预留代替直接扣减。** 用"冻结"字段表达中间态，和 TCC 的思路一致。

**延迟可见。** 中间态数据不进入查询视图（如只查 `status in ('PAID','SHIPPED')`）。

## 5.5 持久化与崩溃恢复

SAGA 协调器必须把每一步的执行结果落库，否则进程崩溃后无法知道"做到哪了"。

```sql
create table saga_instance (
    id           varchar(96) primary key,
    biz_id       varchar(96)  not null,
    definition   varchar(64)  not null,
    status       varchar(24)  not null,   -- RUNNING/COMPENSATING/COMPLETED/COMPENSATED/FAILED
    current_step int          not null,
    payload      varchar(4000) not null,
    error        varchar(1000),
    created_at   timestamp    not null,
    updated_at   timestamp    not null
);

create table saga_step_log (
    saga_id    varchar(96) not null,
    step_name  varchar(64) not null,
    seq        int         not null,
    status     varchar(24) not null,      -- EXECUTED / COMPENSATED
    updated_at timestamp   not null,
    primary key (saga_id, step_name)
);
```

恢复逻辑：

- 状态 `RUNNING`：从 `current_step` 继续正向执行
- 状态 `COMPENSATING` / `FAILED`：继续补偿未完成的步骤

只要"执行一步 → 落库一步"的顺序正确，任何时刻崩溃都能恢复。注意顺序：**先执行、后落库**
会丢一步（重复执行，靠幂等兜底）；**先落库、后执行**会漏一步（永远不执行）。正确做法是执行成功
后立刻落库，并依赖幂等承受"执行成功但落库失败"的重复。

## 5.6 代码骨架

```java
public void forward(String sagaId, SagaDefinition definition, SagaContext context) {
    SagaInstance instance = repository.find(sagaId).orElseThrow();

    for (int i = instance.currentStep(); i < definition.steps().size(); i++) {
        SagaStep step = definition.steps().get(i);
        try {
            RetrySupport.retry(step.name() + ".execute", 3, 20, () -> step.execute(context));
            repository.markStep(sagaId, step.name(), i, StepStatus.EXECUTED);
            repository.updateCurrentStep(sagaId, i + 1);
        } catch (Exception e) {
            compensate(sagaId, definition, context, e.toString());
            return;
        }
    }
    repository.updateStatus(sagaId, SagaStatus.COMPLETED, null);
}
```

补偿按 `seq desc` 取"已执行未补偿"的步骤，逐个执行并标记；有失败则整体置 `FAILED`，
交给恢复任务下一轮继续。

## 5.7 适用

- 长流程、多步骤、步骤之间有外部系统调用（出行：订票 → 订酒店 → 租车）
- 步骤天然可补偿（取消订单、退款、释放额度）
- 能接受最终一致与中间态可见

不适合：步骤不可逆（已发短信、已出库发货）、对隔离性要求高的核心账务。

# 第 6 章 Seata AT：低侵入的两阶段

## 6.1 角色

- **TC（Transaction Coordinator）**：seata-server，维护全局事务与分支事务状态
- **TM（Transaction Manager）**：发起全局事务的一方，标 `@GlobalTransactional`
- **RM（Resource Manager）**：每个参与方，管理分支事务和 `undo_log`

## 6.2 两阶段流程

**一阶段**（业务 SQL 与 undo_log 在同一个本地事务里提交）：

1. 拦截 SQL，解析出表名、主键、before image、after image
2. 执行业务 SQL
3. 写 `undo_log`（xid、branch_id、before/after image）
4. 向 TC 注册分支，申请**全局锁**（表名 + 主键）
5. 提交本地事务（本地锁释放，全局锁仍持有）

**二阶段**：

- 全局提交：TC 通知各 RM **异步删除** undo_log，非常快
- 全局回滚：RM 用 before image 生成反向 SQL 补偿；补偿前校验 after image 与当前数据一致，
  不一致说明被外部脏写，抛异常转人工

## 6.3 undo_log

```sql
create table undo_log (
    branch_id     bigint       not null,
    xid           varchar(128) not null,
    context       varchar(128) not null,
    rollback_info longblob     not null,
    log_status    int          not null,   -- 0 正常，1 全局已完成
    log_created   datetime(6)  not null,
    log_modified  datetime(6)  not null,
    unique key ux_undo_log (xid, branch_id)
);
```

每个被 AT 管理的业务库都要建这张表，且 `rollback_info` 会随更新行的大小增长，需要定期清理
（全局完成后由 TC 异步删除，但异常情况下会残留）。

## 6.4 全局锁与读写隔离

AT 的写隔离靠 TC 维护的全局锁：同一行数据，同一时刻只允许一个全局事务写入。第二个全局事务
拿不到锁会**重试直到超时**，然后回滚。

读隔离默认是**全局读未提交**：一阶段已提交，所以其他事务能读到"已扣减但可能回滚"的数据。
要读到已提交状态，有两种方式：

```java
// 方式一：for update，Seata 会检查全局锁
@Select("select * from account where user_id = #{id} for update")
Account selectForUpdate(Long id);

// 方式二：@GlobalLock + 本地事务
@GlobalLock
@Transactional
public Account query(Long id) { ... }
```

## 6.5 配置

```yaml
seata:
  enabled: true
  application-id: dtx-seata-demo
  tx-service-group: default_tx_group
  enable-auto-data-source-proxy: false
  service:
    vgroup-mapping:
      default_tx_group: default
    grouplist:
      default: 127.0.0.1:8091
  registry:
    type: file
  config:
    type: file
```

多数据源时需要手工包代理：

```java
@Bean
@Primary
public DataSource orderDataSource(@Qualifier("orderRawDataSource") DataSource raw) {
    return new DataSourceProxy(raw);
}
```

业务入口只加一个注解：

```java
@GlobalTransactional(name = "place-order", rollbackFor = Exception.class)
public void place(String orderId, long userId, String sku, int qty, BigDecimal amount) {
    orderRepository.insert(orderId, userId, sku, qty, amount);
    stockRepository.deduct(sku, qty);
    accountRepository.debit(userId, amount);
}
```

## 6.6 限制与坑

| 问题 | 表现 | 应对 |
|---|---|---|
| SQL 解析失败 | 复杂子查询、多表关联更新报错 | 拆简单 SQL，避免存储过程 |
| 热点行 | 全局锁冲突，大量重试超时 | 拆分行、异步化、换 TCC |
| 读未提交 | 读到中间态 | `for update` 或 `@GlobalLock` |
| undo_log 膨胀 | 大字段、批量更新 | 控制单次更新行数，定期清理 |
| TC 单点 | TC 挂掉全局事务卡住 | TC 集群 + 高可用注册中心 |
| 性能损耗 | 每条 SQL 多一次解析 + 一次 undo 写 | 只对核心链路开启 |

**一个常被忽略的点**：AT 的一阶段提交意味着"部分成功"对其他人可见。如果下游逻辑直接读这张表
做决策，可能基于一个最终会回滚的值做判断。要么给读操作加全局锁，要么让下游走消息而不是直读。
