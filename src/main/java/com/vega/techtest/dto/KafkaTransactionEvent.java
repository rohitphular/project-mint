package com.vega.techtest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Kafka transaction event message format
 * This represents the event envelope that wraps transaction data
 * The data field is a generic Map to allow candidates to parse it themselves
 */
public class KafkaTransactionEvent {

    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("eventTimestamp")
    private String eventTimestamp;

    @JsonProperty("source")
    private String source;

    @JsonProperty("version")
    private String version;

    @JsonProperty("data")
    private Map<String, Object> data;

    // Default constructor for Jackson
    public KafkaTransactionEvent() {
    }

    public KafkaTransactionEvent(String eventId, String eventType, String eventTimestamp,
                                 String source, String version, Map<String, Object> data) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.eventTimestamp = eventTimestamp;
        this.source = source;
        this.version = version;
        this.data = data;
    }

    // Getters and Setters
    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(String eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "KafkaTransactionEvent{" +
                "eventId='" + eventId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", eventTimestamp='" + eventTimestamp + '\'' +
                ", source='" + source + '\'' +
                ", version='" + version + '\'' +
                ", data=" + data +
                '}';
    }
} 