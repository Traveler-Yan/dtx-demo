-- Seata AT demo: stock + account database
create database if not exists dtx_stock default character set utf8mb4;
use dtx_stock;

create table if not exists storage (
    sku   varchar(32) primary key,
    stock int         not null
);

create table if not exists account (
    user_id bigint primary key,
    balance decimal(18, 2) not null
);

insert into storage (sku, stock) values ('SKU-1', 100) on duplicate key update stock = values(stock);
insert into account (user_id, balance) values (1001, 1000.00) on duplicate key update balance = values(balance);

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
