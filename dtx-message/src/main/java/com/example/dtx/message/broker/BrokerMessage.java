package com.example.dtx.message.broker;

import java.time.LocalDateTime;

/** A message that reached the broker, kept in memory so tests can assert on it. */
public record BrokerMessage(String topic, String key, String payload, LocalDateTime sentAt) {
}
