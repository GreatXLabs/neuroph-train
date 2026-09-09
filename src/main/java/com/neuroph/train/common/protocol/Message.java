package com.neuroph.train.common.protocol;

import java.util.UUID;

/**
 * Estructura de mensaje intercambiada a través de sockets con serialización JSON.
 */
public class Message {

    private String id;
    private MessageType type;
    private long timestamp;
    private String payload;
    private String error;

    public Message() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String payload) {
        this();
        this.type = type;
        this.payload = payload;
    }

    public Message(MessageType type, String payload, String error) {
        this(type, payload);
        this.error = error;
    }

    public static Message of(MessageType type, String payload) {
        return new Message(type, payload);
    }

    public static Message error(String errorDescription) {
        Message msg = new Message(MessageType.ERROR_RESPONSE, null);
        msg.setError(errorDescription);
        return msg;
    }

    public static Message success(String payload) {
        return new Message(MessageType.SUCCESS_RESPONSE, payload);
    }

    // Getters y Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isError() {
        return type == MessageType.ERROR_RESPONSE || error != null;
    }

    @Override
    public String toString() {
        return "Message{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", timestamp=" + timestamp +
                ", payloadLen=" + (payload != null ? payload.length() : 0) +
                ", error='" + error + '\'' +
                '}';
    }
}
