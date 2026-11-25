drop table if exists saga_step_log;
drop table if exists saga_instance;
drop table if exists points;
drop table if exists account;
drop table if exists inventory;
drop table if exists orders;

create table orders (
    id         varchar(96) primary key,
    user_id    bigint         not null,
    sku        varchar(32)    not null,
    qty        int            not null,
    amount     decimal(18, 2) not null,
    status     varchar(16)    not null,
    created_at timestamp      not null
);

create table inventory (
    sku       varchar(32) primary key,
    available int         not null
);

create table account (
    user_id bigint primary key,
    balance decimal(18, 2) not null
);

create table points (
    user_id bigint primary key,
    points  int    not null
);

create table saga_instance (
    id           varchar(96) primary key,
    biz_id       varchar(96)  not null,
    definition   varchar(64)  not null,
    status       varchar(24)  not null,
    current_step int          not null default 0,
    payload      varchar(4000) not null,
    error        varchar(1000),
    created_at   timestamp    not null,
    updated_at   timestamp    not null
);

create table saga_step_log (
    saga_id   varchar(96) not null,
    step_name varchar(64) not null,
    seq       int         not null,
    status    varchar(24) not null,
    updated_at timestamp  not null,
    primary key (saga_id, step_name)
);
