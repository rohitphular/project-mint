package com.vega.techtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.techtest.dto.KafkaTransactionEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Kafka message deserialization
 * <p>
 * These tests verify that the KafkaTransactionEvent model can properly deserialize
 * the JSON message format documented in the README. This provides candidates with
 * a working example of how to use the model with Jackson.
 */
public class KafkaMessageDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testKafkaMessageDeserialization() throws Exception {
        // Sample Kafka message from README
        String kafkaMessage = """
                {
                  "eventId": "uuid",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2025-06-27T12:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-12345678",
                    "customerId": "CUST-12345",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 25.50,
                    "currency": "GBP",
                    "timestamp": "2025-06-27T12:00:00.000Z",
                    "items": [
                      {
                        "productName": "Milk",
                        "productCode": "MILK001",
                        "unitPrice": 2.50,
                        "quantity": 2,
                        "category": "Dairy"
                      },
                      {
                        "productName": "Bread",
                        "productCode": "BREAD001",
                        "unitPrice": 1.20,
                        "quantity": 1,
                        "category": "Bakery"
                      }
                    ]
                  }
                }
                """;

        // Deserialize the message
        KafkaTransactionEvent event = objectMapper.readValue(kafkaMessage, KafkaTransactionEvent.class);

        // Verify event envelope
        assertNotNull(event);
        assertEquals("uuid", event.getEventId());
        assertEquals("TRANSACTION_CREATED", event.getEventType());
        assertEquals("2025-06-27T12:00:00.000Z", event.getEventTimestamp());
        assertEquals("till-system", event.getSource());
        assertEquals("1.0", event.getVersion());

        // Verify data field is present and contains expected structure
        Map<String, Object> data = event.getData();
        assertNotNull(data);
        assertEquals("TXN-12345678", data.get("transactionId"));
        assertEquals("CUST-12345", data.get("customerId"));
        assertEquals("STORE-001", data.get("storeId"));
        assertEquals("TILL-1", data.get("tillId"));
        assertEquals("card", data.get("paymentMethod"));
        assertEquals(25.50, data.get("totalAmount"));
        assertEquals("GBP", data.get("currency"));
        assertEquals("2025-06-27T12:00:00.000Z", data.get("timestamp"));

        // Verify items array is present
        assertTrue(data.containsKey("items"));
        assertTrue(data.get("items") instanceof java.util.List);
    }

    @Test
    public void testKafkaMessageWithDifferentDataTypes() throws Exception {
        // Test with different data types to ensure robust deserialization
        String kafkaMessage = """
                {
                  "eventId": "test-event-123",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2025-01-15T10:30:00.000Z",
                  "source": "test-till",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-TEST-001",
                    "customerId": "CUST-TEST-001",
                    "storeId": "STORE-TEST-001",
                    "tillId": "TILL-TEST-001",
                    "paymentMethod": "cash",
                    "totalAmount": 15.75,
                    "currency": "USD",
                    "timestamp": "2025-01-15T10:30:00.000Z",
                    "items": [
                      {
                        "productName": "Apple",
                        "productCode": "APPLE001",
                        "unitPrice": 1.25,
                        "quantity": 3,
                        "category": "Fruits"
                      }
                    ]
                  }
                }
                """;

        // Deserialize the message
        KafkaTransactionEvent event = objectMapper.readValue(kafkaMessage, KafkaTransactionEvent.class);

        // Verify the event can be deserialized correctly
        assertNotNull(event);
        assertEquals("test-event-123", event.getEventId());
        assertEquals("TRANSACTION_CREATED", event.getEventType());
        assertEquals("test-till", event.getSource());

        // Verify data types are preserved correctly
        Map<String, Object> data = event.getData();
        assertEquals("TXN-TEST-001", data.get("transactionId"));
        assertEquals(15.75, data.get("totalAmount")); // Should be Double from JSON
        assertEquals("cash", data.get("paymentMethod"));
    }

    @Test
    public void testKafkaMessageSerialization() throws Exception {
        // Test that we can also serialize the model back to JSON
        KafkaTransactionEvent event = new KafkaTransactionEvent(
                "test-serialization",
                "TRANSACTION_CREATED",
                "2025-01-15T10:30:00.000Z",
                "test-source",
                "1.0",
                Map.of(
                        "transactionId", "TXN-SER-001",
                        "totalAmount", 10.50,
                        "currency", "EUR"
                )
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(event);

        // Verify the JSON contains expected fields
        assertTrue(json.contains("test-serialization"));
        assertTrue(json.contains("TRANSACTION_CREATED"));
        assertTrue(json.contains("TXN-SER-001"));
        assertTrue(json.contains("10.5"));

        // Verify we can deserialize it back
        KafkaTransactionEvent deserializedEvent = objectMapper.readValue(json, KafkaTransactionEvent.class);
        assertEquals("test-serialization", deserializedEvent.getEventId());
        assertEquals("TRANSACTION_CREATED", deserializedEvent.getEventType());
    }
} 