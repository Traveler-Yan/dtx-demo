package com.example.dtx.message.domain;

/** Outbox message lifecycle. */
public enum MessageStatus {
    /** Waiting to be delivered. */
    PENDING,
    /** Claimed by a relay thread, delivery in flight. */
    SENDING,
    /** Acknowledged by the broker. */
    SENT,
    /** Delivery attempts exhausted, needs manual handling. */
    DEAD
}
