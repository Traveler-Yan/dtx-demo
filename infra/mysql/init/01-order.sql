-- Seata AT demo: order database
create database if not exists dtx_order default character set utf8mb4;
use dtx_order;

create table if not exists orders (
    id         varchar(64) primary key,
    user_id    bigint         not null,
    sku        varchar(32)    not null,
    qty        int            not null,
    amount     decimal(18, 2) not null,
    status     varchar(16)    not null,
    created_at datetime       not null
);

-- every business database touched by AT needs this table
create table if not exists undo_log (
    branch_id     bigint       not null comment 'branch transaction id',
    xid           varchar(128) not null comment 'global transaction id',
    context       varchar(128) not null comment 'undo_log context',
    rollback_info longblob     not null comment 'rollback info',
    log_status    int          not null comment '0:normal 1:global finished',
    log_created   datetime(6)  not null comment 'create datetime',
    log_modified  datetime(6)  not null comment 'modify datetime',
    unique key ux_undo_log (xid, branch_id)
) engine = Innodb auto_increment = 1 default charset = utf8mb4 comment = 'AT transaction mode undo table';
