# 第 3 章 2PC 与 XA：把提交拆成两步

## 3.1 X/Open DTP 模型

XA 是 X/Open 组织定义的分布式事务规范，模型里有三个角色：

- **AP（Application Program）**：业务应用，决定事务边界
- **RM（Resource Manager）**：资源管理器，通常是数据库，实现 `XAResource`
- **TM（Transaction Manager）**：事务管理器，协调所有 RM

Java 的对应实现是 JTA（`javax.transaction`）与 `XAResource`，开源 TM 有 Atomikos、Narayana、
Bitronix。

## 3.2 两阶段提交

**阶段一 Prepare**：TM 依次调用每个 RM 的 `prepare`。RM 执行事务但**不提交**，把 undo/redo
写入日志、持有行锁，返回"可以提交"或"必须回滚"。

**阶段二 Commit/Rollback**：TM 汇总结果。全部 prepare 成功则调用所有 RM 的 `commit`，
否则调用 `rollback`。

两阶段的本质是：**把"不可逆的提交"推迟到所有人都表态之后**。

## 3.3 三个无法回避的缺陷

**同步阻塞。** `prepare` 之后，RM 上的行锁和连接一直占用到第二阶段结束。事务越长，锁越久，
吞吐越低。这是 XA 在生产上最致命的问题。

**协调者单点。** TM 在阶段二宕机，参与者既不能提交也不能回滚，只能阻塞等待，直到 TM 恢复
或人工介入。

**数据不一致。** 阶段二部分 RM 收到 commit、部分没收到（网络分区或 RM 宕机），集群里就出现
"一半提交一半未提交"。2PC 没有超时自愈机制，只能在恢复后根据 TM 日志继续重放。

## 3.4 三阶段提交

3PC 在 2PC 前加了 CanCommit，并在参与者侧加了超时：

1. CanCommit：询问"能否执行"
2. PreCommit：预提交并持有锁
3. DoCommit：真正提交

参与者在 PreCommit 之后超时未收到 DoCommit，会**默认提交**，从而减少阻塞。但它用"可能不一致"
换"少阻塞"，网络分区下仍然无法保证，且多一轮网络交互，实际生产中几乎不用。

## 3.5 Java 中的 XA

Spring Boot 3 + Atomikos 的多数据源配置：

```xml
<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-spring-boot3-starter</artifactId>
    <version>6.0.0</version>
</dependency>
```

```java
@Bean(initMethod = "init", destroyMethod = "close")
public AtomikosDataSourceBean orderDataSource() {
    AtomikosDataSourceBean ds = new AtomikosDataSourceBean();
    ds.setUniqueResourceName("orderDB");
    ds.setXaDataSourceClassName("com.mysql.cj.jdbc.MysqlXADataSource");
    Properties p = new Properties();
    p.setProperty("URL", "jdbc:mysql://localhost:3306/order_db");
    p.setProperty("user", "root");
    p.setProperty("password", "root");
    ds.setXaProperties(p);
    ds.setPoolSize(10);
    return ds;
}

@Bean
public JtaTransactionManager transactionManager() {
    return new JtaTransactionManager(new UserTransactionManager(), new UserTransactionImp());
}
```

业务代码不需要任何改动，`@Transactional` 由 JTA 接管：

```java
@Transactional
public void transfer(Long from, Long to, BigDecimal amount) {
    orderMapper.debit(from, amount);
    accountMapper.credit(to, amount);
}
```

## 3.6 MySQL 的 XA 有多可靠

MySQL InnoDB 支持 XA，但历史上有两个问题需要知道：

- **binlog 与 XA 的一致性**：5.7 之前，XA 事务在崩溃恢复时可能与 binlog 不一致，导致主从数据
  差异。5.7 引入 `XA PREPARE` 的 binlog 记录方式后有较大改进，8.0 继续完善。
- **连接占用**：`XA START ... XA END ... XA PREPARE` 期间连接和锁都被占用，连接池很容易被打满。

因此 MySQL 上的 XA 通常只用于**短事务、低并发、强一致**的场景，比如核心记账。高并发交易
一般直接跳到 TCC 或 AT。

## 3.7 适用边界

适合：

- 事务执行时间短（毫秒级），并发不高
- 资源本身支持 XA（Oracle、DB2、MySQL 8）
- 已有 JTA 容器或愿意引入 Atomikos/Narayana
- 对强一致的要求高于吞吐

不适合：

- 高并发、长事务、热点行
- 需要跨 HTTP 服务（XA 不能跨进程边界，除非用 WS-AT 这类重协议）
- 中间件不支持 XA（多数 MQ 的 XA 支持不完整）

# 第 4 章 TCC：把两阶段下沉到业务

## 4.1 模型

TCC 要求每个参与者实现三个方法：

- **Try**：检查并**预留**资源（冻结库存、冻结余额），不真正扣减
- **Confirm**：使用预留资源完成真正的业务，必须成功且幂等
- **Cancel**：释放预留资源，必须幂等且允许空回滚

与 2PC 的区别在于：**2PC 的隔离性由数据库锁提供，TCC 的隔离性由业务自己通过"预留"实现**。
因此 TCC 没有长事务锁，性能好得多，代价是业务改造量大。

## 4.2 一次完整的 TCC 时序

以"下单扣库存扣余额"为例：

| 步骤 | 协调者 | 库存参与者 | 账户参与者 |
|---|---|---|---|
| 1 | 生成 xid，调用 Try | 冻结 2 件（available-2, frozen+2） | — |
| 2 | 调用 Try | — | 冻结 100 元（balance-100, frozen+100） |
| 3 | 全部成功，调用 Confirm | frozen-2 | frozen-100 |
| 3' | 任一步失败，逆序 Cancel | 解冻 2 件 | 解冻 100 元 |

注意第 3 步：Confirm 阶段如果失败，**不能回滚**（因为部分分支可能已经 Confirm），只能重试 +
对账。这是所有两阶段方案的共同软肋。

## 4.3 三个必须解决的问题

### 空回滚

Try 没有执行（请求丢失、参与者宕机），Cancel 却先到了。此时 Cancel 去解冻一份不存在的资源，
要么报错、要么把别人的资源解冻了。

**解法**：Cancel 时先查控制表，没有 Try 记录就插入一条 `EMPTY_ROLLBACK` 记录并直接返回成功。

### 悬挂

Cancel 先执行（空回滚），随后延迟的 Try 请求到达并执行成功。资源被预留，但再也没有 Confirm
或 Cancel 来释放它——这就是悬挂。

**解法**：Try 执行前查控制表，若状态已经是 `CANCEL` 或 `EMPTY_ROLLBACK`，直接拒绝执行。

### 幂等

网络重试、协调者重放都会让 Confirm/Cancel 被调用多次。扣两次钱就是事故。

**解法**：`(xid, branch_id)` 唯一键 + 状态机，只有合法状态才推进。

## 4.4 控制表与状态机

```sql
create table tcc_transaction (
    xid        varchar(96) not null,
    branch_id  varchar(96) not null,
    status     varchar(16) not null,   -- TRY / CONFIRM / CANCEL / EMPTY_ROLLBACK
    created_at timestamp   not null,
    updated_at timestamp   not null,
    primary key (xid, branch_id)
);
```

状态转移只有四条合法路径：

| 当前状态 | 收到 | 结果 |
|---|---|---|
| 无记录 | Try | 插入 TRY，预留资源 |
| TRY | Confirm | 扣减预留，置 CONFIRM |
| TRY | Cancel | 释放预留，置 CANCEL |
| 无记录 | Cancel | 插入 EMPTY_ROLLBACK，直接成功 |
| CANCEL / EMPTY_ROLLBACK | Try | 抛异常，拒绝（防悬挂） |
| 任意终态 | 重复 Confirm/Cancel | 直接返回（幂等） |

## 4.5 代码：Try 的三重判断

```java
@Transactional
public void tryDeduct(String xid, String branchId, String sku, int qty) {
    Optional<TccTransaction> existing =
            tccRepository.lockExistingOrCreate(xid, branchId, TccStatus.TRY);

    if (existing.isPresent()) {
        TccStatus status = existing.get().status();
        if (status == TccStatus.TRY || status == TccStatus.CONFIRM) {
            return;                                  // 幂等：重复 Try
        }
        throw new TccSuspensionException(xid, branchId);  // 防悬挂
    }

    int updated = jdbc.update(
            "update inventory set available = available - ?, frozen = frozen + ? "
                    + "where sku = ? and available >= ?", qty, qty, sku, qty);
    if (updated == 0) {
        throw new BizException("INVENTORY_NOT_ENOUGH", "sku " + sku + " cannot reserve " + qty);
    }
}
```

`lockExistingOrCreate` 内部先用 `select ... for update` 锁行，再决定插入；并发下插入冲突由主键
兜底，失败方重新读取。`@Transactional` 保证"控制表 + 资源表"在同一个本地事务里。

## 4.6 代码：Cancel 的空回滚

```java
@Transactional
public void cancelDeduct(String xid, String branchId, String sku, int qty) {
    Optional<TccTransaction> row = tccRepository.selectForUpdate(xid, branchId);
    if (row.isEmpty()) {
        tccRepository.insert(xid, branchId, TccStatus.EMPTY_ROLLBACK);
        return;                                     // 空回滚
    }
    TccStatus status = row.get().status();
    if (status.isCancelled()) {
        return;                                     // 幂等
    }
    if (status == TccStatus.CONFIRM) {
        return;                                     // 已确认，不能再取消
    }

    jdbc.update("update inventory set frozen = frozen - ?, available = available + ? "
            + "where sku = ? and frozen >= ?", qty, qty, sku, qty);
    tccRepository.updateStatus(xid, branchId, TccStatus.CANCEL);
}
```

## 4.7 设计要点

**Try 的粒度要小。** Try 只做"预留"，不做真正的业务动作；复杂校验放在 Try，真正的副作用放在
Confirm。

**预留要可查询。** 冻结字段必须能被查询和统计，否则无法对账，也无法排查"钱被冻住了"的客诉。

**Confirm 必须能一直重试。** 生产上 Confirm 失败通常不是业务失败，而是网络或下游抖动。
协调者要做指数退避重试，超过阈值后落到"待人工"队列。

**超时时间要分层。** Try 超时 < 全局超时 < 补偿任务扫描周期。

## 4.8 代价与适用

TCC 的优点：无长事务锁、性能好、隔离性可控、能跨 HTTP 服务。

代价：

- 每个参与者要写三个方法，业务侵入最重
- 要维护控制表、冻结字段、对账逻辑
- 空回滚、悬挂、幂等三件事必须逐个处理，漏一个就是资损

适用：资金、库存等**必须预留**的核心业务；跨公司合作（支付、票务、供应链）。
