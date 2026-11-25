package com.example.dtx.message.broker;

/** The only thing the outbox relay needs from a message middleware. */
public interface MessageBroker {

    void send(String topic, String key, String payload) throws Exception;
}
