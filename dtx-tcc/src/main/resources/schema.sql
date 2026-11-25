-- TCC demo schema (H2). Drop + create keeps the demo repeatable.
drop table if exists tcc_transaction;
drop table if exists orders;
drop table if exists account;
drop table if exists inventory;

create table inventory (
    sku       varchar(32) primary key,
    available int           not null,
    frozen    int           not null default 0
);

create table account (
    user_id bigint primary key,
    balance decimal(18, 2) not null,
    frozen  decimal(18, 2) not null default 0
);

create table orders (
    id         varchar(64) primary key,
    user_id    bigint         not null,
    sku        varchar(32)    not null,
    qty        int            not null,
    amount     decimal(18, 2) not null,
    status     varchar(16)    not null,
    created_at timestamp      not null
);

-- The control table that makes Try/Confirm/Cancel idempotent and prevents
-- empty rollback + suspension.
create table tcc_transaction (
    xid        varchar(96) not null,
    branch_id  varchar(96) not null,
    status     varchar(16) not null,
    created_at timestamp   not null,
    updated_at timestamp   not null,
    primary key (xid, branch_id)
);
