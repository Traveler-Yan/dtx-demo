# 附录 A 常用 DDL 汇总

## A.1 TCC 控制表

```sql
create table tcc_transaction (
    xid        varchar(96) not null,
    branch_id  varchar(96) not null,
    status     varchar(16) not null,
    created_at timestamp   not null,
    updated_at timestamp   not null,
    primary key (xid, branch_id)
);
```

## A.2 SAGA 实例与步骤日志

```sql
create table saga_instance (
    id           varchar(96)   primary key,
    biz_id       varchar(96)   not null,
    definition   varchar(64)   not null,
    status       varchar(24)   not null,
    current_step int           not null default 0,
    payload      varchar(4000) not null,
    error        varchar(1000),
    created_at   timestamp     not null,
    updated_at   timestamp     not null
);

create table saga_step_log (
    saga_id    varchar(96) not null,
    step_name  varchar(64) not null,
    seq        int         not null,
    status     varchar(24) not null,
    updated_at timestamp   not null,
    primary key (saga_id, step_name)
);
```

## A.3 本地消息表与幂等表

```sql
create table local_message (
    id            varchar(64)   primary key,
    biz_type      varchar(32)   not null,
    biz_key       varchar(64)   not null,
    topic         varchar(128)  not null,
    payload       varchar(4000) not null,
    status        varchar(16)   not null,
    retry_count   int           not null default 0,
    max_retry     int           not null default 5,
    next_retry_at timestamp     not null,
    msg_version   int           not null default 0,
    created_at    timestamp     not null
);

create index idx_local_message_pending on local_message (status, next_retry_at);

create table idempotent_record (
    biz_type   varchar(32) not null,
    biz_key    varchar(64) not null,
    created_at timestamp   not null,
    primary key (biz_type, biz_key)
);
```

## A.4 Seata undo_log

```sql
create table undo_log (
    branch_id     bigint       not null,
    xid           varchar(128) not null,
    context       varchar(128) not null,
    rollback_info longblob     not null,
    log_status    int          not null,
    log_created   datetime(6)  not null,
    log_modified  datetime(6)  not null,
    unique key ux_undo_log (xid, branch_id)
) engine = InnoDB default charset = utf8mb4;
```

# 附录 B 面试速查

**1. 2PC 和 TCC 的区别？**
2PC 是资源层协议，隔离性由数据库锁保证，一阶段持有锁直到二阶段结束；TCC 是业务层协议，
靠资源预留实现隔离，无长事务锁，但业务侵入大。

**2. Seata AT 一阶段已经提交，为什么还能回滚？**
一阶段把 before/after image 写进 undo_log，回滚时用 before image 生成反向 SQL 补偿，
并用 after image 校验是否被脏写。

**3. AT 的读隔离怎么解决？**
默认全局读未提交；用 `select ... for update`（会检查全局锁）或 `@GlobalLock` + 本地事务
实现读已提交。

**4. TCC 的空回滚和悬挂怎么解决？**
控制表 + 状态机：Cancel 查不到 Try 记录时插入 EMPTY_ROLLBACK 并返回；Try 发现已是
CANCEL/EMPTY_ROLLBACK 时拒绝执行。

**5. TCC 的 Confirm 失败了怎么办？**
不能回滚（可能部分分支已确认），只能重试 + 落库 + 对账 + 人工。这是两阶段方案的共同软肋。

**6. RocketMQ 事务消息能保证消费者一定成功吗？**
不能。它只保证"本地事务与消息发送"的原子性，消费端要靠重试 + 幂等 + 对账。

**7. 为什么不能用 afterCommit 发消息？**
事务已提交，发送失败无法回滚，消息永久丢失。

**8. 本地消息表和事务消息怎么选？**
有 RocketMQ 且追求低延迟用事务消息；需要通用、可审计、异构 MQ 用本地消息表（可配 CDC）。

**9. SAGA 没有隔离性怎么办？**
语义锁（状态字段）、条件更新（`where status=?`）、预留字段、延迟可见。

**10. 幂等的本质是什么？**
同一请求执行 N 次与执行 1 次的最终状态相同。实现靠唯一键、状态机、去重表。

**11. 分布式事务能保证 100% 一致吗？**
不能。工程上追求的是"最终一致 + 可发现 + 可修复"，对账是最后一道防线。

**12. 为什么说 XA 性能差？**
prepare 后锁和连接一直占用到 commit/rollback，同步阻塞，且协调者是单点。

**13. Seata AT 的全局锁是什么粒度？**
表名 + 主键值，由 TC 维护，用于写隔离。

**14. 全局事务超时了会怎样？**
TC 主动回滚，各分支执行 undo；已经 confirm 的分支无法回滚，进入不一致状态，需对账。

**15. 消息重复消费怎么处理？**
去重表（与业务同事务）、状态机、唯一索引；Redis 只能做前置拦截。

**16. 补偿任务失败多次怎么办？**
指数退避 + 上限 + 死信表 + 告警 + 人工工单，不能无限重试。

**17. 什么情况下不应该用分布式事务？**
能合并服务、能同库、能改成最终一致 + 对账的时候。

**18. TCC 的 Try 应该做多少事？**
只做校验和预留，不做真正的业务副作用；副作用留给 Confirm。

**19. Seata AT 支持哪些数据库？**
MySQL、Oracle、PostgreSQL、TiDB 等；H2、MongoDB 不支持。

**20. 如何验证分布式事务实现是对的？**
构造故障注入：Try 后宕机、Confirm 超时、消息重复、进程崩溃，然后断言数据库最终状态。
本工程的 21 个用例就是这种思路。
