drop table if exists idempotent_record;
drop table if exists local_message;
drop table if exists points;
drop table if exists orders;

create table orders (
    id         varchar(64) primary key,
    user_id    bigint         not null,
    sku        varchar(32)    not null,
    qty        int            not null,
    amount     decimal(18, 2) not null,
    status     varchar(16)    not null,
    created_at timestamp      not null
);

create table points (
    user_id bigint primary key,
    points  int    not null
);

-- Outbox: written in the same local transaction as the business row.
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

-- Consumer side de-duplication.
create table idempotent_record (
    biz_type   varchar(32) not null,
    biz_key    varchar(64) not null,
    created_at timestamp   not null,
    primary key (biz_type, biz_key)
);
