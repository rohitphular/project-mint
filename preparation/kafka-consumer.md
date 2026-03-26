# Kafka Consumer -- Definitive Implementation Guide

**Interview context:** Vega pair programming tech test.
**Time budget:** 40-50 min total (dependency+config 5 min, skeleton 5 min, data mapping 15 min, testing/verification 10 min, discussion 5-10 min).
**This is the senior-level gate.** Building this end-to-end -- including poison pill handling, idempotency awareness, and the ability to discuss production trade-offs -- is what separates mid from senior.

---

## Section 1: Overview

### Architecture

```
PATH 1 (REST -- already working):
  Till Simulator (Python) --POST--> TransactionController --> TransactionService --> PostgreSQL

PATH 2 (Kafka -- BROKEN by design, no consumer exists):
  Kafka Producer (Python) --publish--> 'transactions' topic --> [YOUR CONSUMER] --> TransactionService --> PostgreSQL
```

### What Already Exists

| Component | File | Purpose |
|-----------|------|---------|
| Python Kafka producer | `kafka-producer/kafka_producer.py` | Publishes 5 msg/sec to `transactions` topic with event envelope |
| Event envelope DTO | `dto/KafkaTransactionEvent.java` | `Map<String, Object> data` -- deliberately untyped |
| Deserialization test | `KafkaMessageDeserializationTest.java` | Blueprint showing Jackson + KafkaTransactionEvent |
| Transaction service | `service/TransactionService.java` | `processTransaction(TransactionRequest)` -- persistence + duplicate detection |
| Transaction DTOs | `dto/TransactionRequest.java`, `dto/TransactionItemRequest.java` | Target types for mapping |
| Seed data + poison pill | `scripts/start-all.sh` | 10 valid transactions + 1 malformed `BAD-TXN` |
| Kafka infrastructure | `docker-compose.yml` | Zookeeper, Kafka (port 9092), Kafka UI (port 8081) |
| `spring-kafka-test` | Not in build.gradle | Intentionally absent -- you must add `spring-kafka` |

### What You Build

1. Add `spring-kafka` dependency to `build.gradle`
2. Add Kafka consumer config to `application.yml`
3. Create `TransactionKafkaConsumer.java` -- consumer class with `@KafkaListener`

### How the Interviewer Will Frame It

> "The REST API is working. Now we want to also process transactions coming in through Kafka. There is a Python producer already publishing to a `transactions` topic. Can you build a consumer that picks those messages up and persists them through the existing TransactionService?"

---

## Section 2: Step-by-Step Implementation

### Step 2.1: Add Dependency

**File:** `build.gradle` -- add after the JSON Processing block (around line 38)

```groovy
// Kafka
implementation 'org.springframework.kafka:spring-kafka'
```

**Talking point:** "I see the test dependencies don't include `spring-kafka-test` yet either, so I will add that too if we write an integration test."

**Critical naming note:** The dependency is `spring-kafka`, NOT `spring-boot-starter-kafka`. There is no starter with that name. Spring Boot starters follow the pattern `spring-boot-starter-{name}`, but Kafka is the exception -- the project is called Spring for Apache Kafka and its artifact is `org.springframework.kafka:spring-kafka`. The Spring Boot BOM manages the version, so no version number is needed.

**The clue:** The existing `build.gradle` has no Kafka dependencies at all in the `implementation` block. The absence is deliberate -- building the consumer is the interview task.

---

### Step 2.2: Add Configuration

**File:** `src/main/resources/application.yml` -- add under `spring:`, after the `liquibase:` block (line 27)

```yaml
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: tech-test-consumer
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      enable-auto-commit: false
    listener:
      ack-mode: record
```

**Config rationale:**

| Setting | Value | Why |
|---------|-------|-----|
| `bootstrap-servers` | `localhost:9092` | App runs on the host, not inside Docker. Docker-internal address is `kafka:29092` (docker-compose.yml line 41: `KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,PLAINTEXT_HOST://kafka:29092`). Using the wrong address causes `TimeoutException`. |
| `group-id` | `tech-test-consumer` | Names this consumer group. All instances with the same group share partition assignments. |
| `auto-offset-reset` | `earliest` | Process ALL messages from the beginning on first start. The producer has been running and building a backlog. If `latest`, you miss the entire backlog. This setting only applies when there is no committed offset for the consumer group -- after the first successful consumption, offsets are committed and this is ignored. |
| `key-deserializer` | `StringDeserializer` | Producer uses `transactionId` (String) as the message key. |
| `value-deserializer` | `StringDeserializer` | Receive raw JSON as String, then parse manually with Jackson. This gives full control over error handling -- malformed messages hit your try/catch instead of crashing the deserializer. See Section 7 for the JsonDeserializer alternative. |
| `enable-auto-commit` | `false` | Do not auto-commit offsets. Spring Kafka manages commits after the listener method returns successfully. |
| `ack-mode` | `record` | Acknowledge each record individually after processing. This gives at-least-once delivery: if the consumer crashes mid-batch, only unacknowledged records are redelivered. |

---

### Step 2.3: Create Consumer Class

**New file:** `src/main/java/com/vega/techtest/consumer/TransactionKafkaConsumer.java`

Build it in this order during the interview:

#### Phase 1: Class skeleton with injection

```java
package com.vega.techtest.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.techtest.dto.KafkaTransactionEvent;
import com.vega.techtest.dto.TransactionItemRequest;
import com.vega.techtest.dto.TransactionRequest;
import com.vega.techtest.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TransactionKafkaConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TransactionKafkaConsumer.class);

    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    public TransactionKafkaConsumer(TransactionService transactionService,
                                    ObjectMapper objectMapper) {
        this.transactionService = transactionService;
        this.objectMapper = objectMapper;
    }
```

**Talking point:** "I am injecting the Spring-managed ObjectMapper rather than creating a new one. The Spring Boot auto-configured ObjectMapper already has the JavaTimeModule registered (from the `jackson-datatype-jsr310` dependency on line 37 of build.gradle), which we will need later for timestamp handling."

#### Phase 2: The consume method

```java
    @KafkaListener(topics = "transactions", groupId = "tech-test-consumer")
    public void consume(String message) {
        try {
            logger.info("Received Kafka message");

            // Step 1: Deserialize the event envelope
            KafkaTransactionEvent event = objectMapper.readValue(message,
                    KafkaTransactionEvent.class);

            // Step 2: Extract and validate the data payload
            Map<String, Object> data = event.getData();
            if (data == null) {
                logger.warn("Event data is null for eventId: {}, skipping",
                        event.getEventId());
                return;
            }

            // Step 3: Map untyped data to TransactionRequest
            TransactionRequest request = mapToTransactionRequest(data);

            // Step 4: Process (idempotency handled by TransactionService)
            transactionService.processTransaction(request);

            logger.info("Successfully processed Kafka transaction: {}",
                    request.getTransactionId());

        } catch (IllegalArgumentException e) {
            // Duplicate transactionId or validation failure -- deterministic, do NOT retry
            logger.warn("Skipping Kafka message (validation/duplicate): {}", e.getMessage());
        } catch (Exception e) {
            // Malformed JSON, mapping error, unexpected failure
            // Log and move on -- do NOT rethrow or the consumer retries forever
            logger.error("Error processing Kafka message: {}", e.getMessage(), e);
        }
    }
```

**Critical design decisions to mention:**

1. **Catch-all with no rethrow:** If you rethrow, the consumer retries the same offset forever (poison pill scenario). Catch, log, and let the offset advance. In production, you would send to a dead letter topic before advancing.

2. **IllegalArgumentException separate catch:** `TransactionService.processTransaction()` throws this for duplicate transactionIds (line 51 of TransactionService.java). This is an expected, deterministic failure -- logging at WARN, not ERROR.

3. **Null check on `data`:** The poison pill message from `start-all.sh` is flat JSON with no envelope, so `event.getData()` returns null. This null check handles it.

#### Phase 3: Data mapping methods (the hard part -- see Step 2.4)

#### Phase 4: Close the class

```java
}
```

---

### Step 2.4: Data Mapping -- The Hard Part

This is where you will spend the most time (15 min budget). The `KafkaTransactionEvent.data` field is `Map<String, Object>` -- Jackson deserializes everything into default Java types.

#### Field Mapping Table

| Source (Map key) | Jackson Default Type | Target Field | Target Type | Conversion Required |
|-----------------|---------------------|--------------|-------------|-------------------|
| `transactionId` | `String` | `TransactionRequest.transactionId` | `String` | Direct cast |
| `customerId` | `String` | `TransactionRequest.customerId` | `String` | Direct cast |
| `storeId` | `String` | `TransactionRequest.storeId` | `String` | Direct cast |
| `tillId` | `String` | `TransactionRequest.tillId` | `String` | Direct cast |
| `paymentMethod` | `String` | `TransactionRequest.paymentMethod` | `String` | Direct cast |
| `totalAmount` | `Double` (e.g., 25.5) | `TransactionRequest.totalAmount` | `BigDecimal` | `BigDecimal.valueOf(number.doubleValue())` |
| `currency` | `String` | `TransactionRequest.currency` | `String` | Direct cast, default `"GBP"` |
| `timestamp` | `String` (ISO 8601) | `TransactionRequest.timestamp` | `ZonedDateTime` | `ZonedDateTime.parse(string)` |
| `items` | `List<LinkedHashMap>` | `TransactionRequest.items` | `List<TransactionItemRequest>` | Manual iteration + per-field mapping |

**Item sub-fields:**

| Source (Map key) | Jackson Default Type | Target Field | Target Type | Conversion Required |
|-----------------|---------------------|--------------|-------------|-------------------|
| `productName` | `String` | `TransactionItemRequest.productName` | `String` | Direct cast |
| `productCode` | `String` | `TransactionItemRequest.productCode` | `String` | Direct cast |
| `unitPrice` | `Double` | `TransactionItemRequest.unitPrice` | `BigDecimal` | `BigDecimal.valueOf(number.doubleValue())` |
| `quantity` | `Integer` | `TransactionItemRequest.quantity` | `Integer` | `((Number) obj).intValue()` |
| `category` | `String` | `TransactionItemRequest.category` | `String` | Direct cast |

#### The mapToTransactionRequest Method

```java
    private TransactionRequest mapToTransactionRequest(Map<String, Object> data) {
        TransactionRequest request = new TransactionRequest();

        request.setTransactionId((String) data.get("transactionId"));
        request.setCustomerId((String) data.get("customerId"));
        request.setStoreId((String) data.get("storeId"));
        request.setTillId((String) data.get("tillId"));
        request.setPaymentMethod((String) data.get("paymentMethod"));
        request.setCurrency((String) data.getOrDefault("currency", "GBP"));

        // CRITICAL: totalAmount comes as Double from JSON
        // Use BigDecimal.valueOf(double) -- NOT new BigDecimal(double)
        Object totalAmountObj = data.get("totalAmount");
        if (totalAmountObj instanceof Number number) {
            request.setTotalAmount(BigDecimal.valueOf(number.doubleValue()));
        }

        // CRITICAL: timestamp comes as ISO String, not ZonedDateTime
        Object timestampObj = data.get("timestamp");
        if (timestampObj instanceof String ts) {
            request.setTimestamp(ZonedDateTime.parse(ts));
        }

        // Items come as List<LinkedHashMap> -- manual conversion needed
        Object itemsObj = data.get("items");
        if (itemsObj instanceof List<?> rawItems) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> itemMaps = (List<Map<String, Object>>) rawItems;
            List<TransactionItemRequest> items = itemMaps.stream()
                    .map(this::mapToItemRequest)
                    .collect(Collectors.toList());
            request.setItems(items);
        }

        return request;
    }
```

#### The mapToItemRequest Method

```java
    private TransactionItemRequest mapToItemRequest(Map<String, Object> itemMap) {
        TransactionItemRequest item = new TransactionItemRequest();

        item.setProductName((String) itemMap.get("productName"));
        item.setProductCode((String) itemMap.get("productCode"));
        item.setCategory((String) itemMap.get("category"));

        Object priceObj = itemMap.get("unitPrice");
        if (priceObj instanceof Number number) {
            item.setUnitPrice(BigDecimal.valueOf(number.doubleValue()));
        }

        Object qtyObj = itemMap.get("quantity");
        if (qtyObj instanceof Number number) {
            item.setQuantity(number.intValue());
        }

        return item;
    }
```

#### Why BigDecimal.valueOf(double) and NOT new BigDecimal(double)

```java
new BigDecimal(7.69)              // = 7.6900000000000003552713678800500929355621337890625
BigDecimal.valueOf(7.69)          // = 7.69
```

`new BigDecimal(double)` captures the full IEEE 754 binary representation of the double. The number 7.69 cannot be represented exactly in binary floating point, so the constructor faithfully records all 53 bits of imprecision.

`BigDecimal.valueOf(double)` calls `Double.toString(double)` first, which produces `"7.69"`, then passes that string to the `BigDecimal(String)` constructor. The result is the human-expected value.

**In financial systems, `new BigDecimal(double)` is a well-known anti-pattern.** This is one of the first things a reviewer checks.

**Talking point:** "In this codebase, the producer calculates totals with Python floats and rounds to 2 decimal places before serialization. Jackson deserializes JSON numbers as Java doubles. `BigDecimal.valueOf()` goes through `Double.toString()` internally, which gives us the rounded human-readable value rather than the IEEE 754 binary expansion."

#### Why instanceof Number instead of direct (Double) cast

The JSON spec does not distinguish integer from floating-point numbers. Jackson deserializes:
- `25.50` as `Double`
- `25` (no decimal) as `Integer`
- Large numbers as `Long`

Using `instanceof Number` with `.doubleValue()` or `.intValue()` handles all three cases safely. A direct `(Double)` cast would throw `ClassCastException` if Jackson happened to deserialize a whole number as `Integer`.

---

### Step 2.5: Verification

After implementation, verify in this order:

```bash
# 1. Infrastructure should already be running
docker compose ps

# 2. Build and run the application
./gradlew bootRun

# 3. Watch the console -- you should see consumer log messages:
#    "Received Kafka message"
#    "Successfully processed Kafka transaction: TXN-001"
#    ... through TXN-010
#    "Error processing Kafka message: ..." for BAD-TXN

# 4. Check the stats endpoint -- should show transactions from both REST and Kafka
curl http://localhost:8080/api/transactions/stats/STORE-001

# 5. Check Kafka UI at http://localhost:8081
#    - Click "transactions" topic
#    - Consumer group "tech-test-consumer" should show lag = 0

# 6. Verify the poison pill was handled gracefully (check logs, no crash)
```

**What to look for in the logs:**
- 10 "Successfully processed" messages (TXN-001 through TXN-010)
- 1 error for BAD-TXN (either null data or mapping failure -- both are correct)
- No consumer crash or retry loop
- If the producer container is running, you will see continuous new transactions

---

## Section 3: The Poison Pill (BAD-TXN)

### What It Is

The `start-all.sh` script (line 78) seeds a deliberately malformed message into the `transactions` topic:

```json
{"transactionId":"BAD-TXN","timestamp":"INVALID-DATE","customerId":"","items":[],"total":"not-a-number","paymentMethod":"","storeId":""}
```

### Why It Breaks

| Field | Problem |
|-------|---------|
| `timestamp` | `"INVALID-DATE"` -- `ZonedDateTime.parse()` throws `DateTimeParseException` |
| `total` | `"not-a-number"` (String, not a number) -- `instanceof Number` check fails, `totalAmount` stays null |
| `customerId` | Empty string `""` |
| `storeId` | Empty string `""` -- `TransactionService.validateTransactionRequest()` throws `IllegalArgumentException("Store ID is required")` |
| `items` | Empty array `[]` -- technically valid but no items to process |
| Format | **Flat JSON, no event envelope** -- no `eventId`, `eventType`, `source`, `version`, `data` wrapper |

### Why Your Consumer Handles It

The flat format is the critical issue. When Jackson deserializes this into `KafkaTransactionEvent`:
- `eventId` = null
- `eventType` = null
- `data` = null (the JSON has `transactionId` at the top level, not nested under `data`)

Your null check catches it:
```java
Map<String, Object> data = event.getData();
if (data == null) {
    logger.warn("Event data is null for eventId: {}, skipping", event.getEventId());
    return;
}
```

The consumer logs a warning and moves on. No crash. No retry loop. The offset advances.

**This is DELIBERATE.** The interviewers planted this to test that your consumer is resilient to malformed input. Mention it proactively: "I notice the seed data includes a poison pill. My consumer handles it because of the null check on `event.getData()` and the broad exception handler."

---

## Section 4: The Second Topic (tech-test-topic)

### Discovery

`start-all.sh` lines 57-58 create TWO topics:

```bash
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic tech-test-topic --partitions 3 --replication-factor 1

docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic transactions --partitions 3 --replication-factor 1
```

### Evidence: Completely Unused

| Where searched | References to `tech-test-topic` |
|---|---|
| All Java source | 0 |
| application.yml | 0 |
| build.gradle | 0 |
| kafka_producer.py | 0 (publishes only to `transactions`) |
| docker-compose.yml | 0 |
| All README/docs | 0 |

Nobody publishes to it. Nobody consumes from it. It has 3 partitions matching the `transactions` topic.

### Most Likely Purpose: Pre-provisioned Dead Letter Topic

Supporting evidence:
- Seed data includes a deliberate poison pill (`BAD-TXN`)
- Matching partition count (3) enables same-partition routing
- Spring Kafka's `DeadLetterPublishingRecoverer` is a standard pattern

### When to Mention It

**During error handling discussion:** "I noticed the startup script pre-creates a second topic called `tech-test-topic`. I would use that as a dead letter topic for messages that fail after N retries. This way failed messages are preserved for investigation without blocking the main consumer."

**Code snippet (reference only, do not implement unless asked):**

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
        kafkaTemplate,
        (record, ex) -> new TopicPartition("tech-test-topic", record.partition())
    );
    return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
}
```

---

## Section 5: Idempotency and Duplicate Handling

### How It Works Today

`TransactionService.processTransaction()` (line 50-52):
```java
if (transactionRepository.existsByTransactionId(transactionId)) {
    throw new IllegalArgumentException("Transaction ID already exists: " + transactionId);
}
```

The consumer catches `IllegalArgumentException` at WARN level and moves on.

### Consumer Restart Scenario

1. Consumer processes TXN-001 through TXN-005, offsets committed
2. Consumer crashes
3. Consumer restarts, picks up from last committed offset (TXN-006)
4. If the crash happened after processing TXN-006 but before offset commit, TXN-006 is redelivered
5. `existsByTransactionId("TXN-006")` returns true, `IllegalArgumentException` is thrown, consumer logs warning and skips

This is the **at-least-once delivery + application-level deduplication** pattern. Standard for financial systems.

### What to Say

> "Kafka provides at-least-once delivery by default. A message may be redelivered if the consumer crashes after processing but before committing the offset. The `existsByTransactionId` check in TransactionService gives us effectively-once semantics at the application level. This is the standard pattern in financial processing -- idempotent consumers are safer and simpler than relying on Kafka's exactly-once semantics, which require transactional producers and `isolation.level=read_committed`, adding significant latency and operational complexity."

### Production Improvement: Transactional Outbox

For true atomicity between the database write and the Kafka offset commit:
- Write to an outbox table in the same database transaction
- A separate process tails the outbox and publishes to Kafka
- This eliminates the dual-write problem (DB write succeeds, offset commit fails)

Mention this as a production consideration, not something to implement in the interview.

---

## Section 6: Test Cases

### 6.1: Unit Test -- Consumer Logic

**New file:** `src/test/java/com/vega/techtest/consumer/TransactionKafkaConsumerTest.java`

```java
package com.vega.techtest.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.techtest.dto.TransactionRequest;
import com.vega.techtest.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionKafkaConsumerTest {

    @Mock
    private TransactionService transactionService;

    private TransactionKafkaConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new TransactionKafkaConsumer(transactionService, objectMapper);
    }

    @Test
    void shouldProcessValidKafkaMessage() {
        // Given -- valid event envelope matching producer format
        String message = """
                {
                  "eventId": "test-event-1",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2026-03-26T10:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-TEST001",
                    "customerId": "CUST-001",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 7.69,
                    "currency": "GBP",
                    "timestamp": "2026-03-26T10:00:00.000Z",
                    "items": [
                      {
                        "productName": "Milk",
                        "productCode": "MILK001",
                        "unitPrice": 2.50,
                        "quantity": 1,
                        "category": "Dairy"
                      },
                      {
                        "productName": "Bread",
                        "productCode": "BREAD001",
                        "unitPrice": 1.73,
                        "quantity": 3,
                        "category": "Bakery"
                      }
                    ]
                  }
                }
                """;

        // When
        consumer.consume(message);

        // Then
        ArgumentCaptor<TransactionRequest> captor =
                ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService, times(1)).processTransaction(captor.capture());

        TransactionRequest captured = captor.getValue();
        assertEquals("TXN-TEST001", captured.getTransactionId());
        assertEquals("CUST-001", captured.getCustomerId());
        assertEquals("STORE-001", captured.getStoreId());
        assertEquals("TILL-1", captured.getTillId());
        assertEquals("card", captured.getPaymentMethod());
        assertEquals("GBP", captured.getCurrency());
        assertNotNull(captured.getTimestamp());
        assertEquals(2, captured.getItems().size());
    }

    @Test
    void shouldHandleNullDataGracefully() {
        // Given -- flat JSON with no event envelope (like the poison pill)
        String message = """
                {
                  "transactionId": "BAD-TXN",
                  "timestamp": "INVALID-DATE",
                  "customerId": "",
                  "items": [],
                  "total": "not-a-number"
                }
                """;

        // When
        consumer.consume(message);

        // Then -- processTransaction should NOT be called
        verify(transactionService, never()).processTransaction(any());
    }

    @Test
    void shouldHandleMalformedJsonGracefully() {
        // Given -- completely invalid JSON
        String message = "this is not json at all {{{";

        // When -- should not throw
        consumer.consume(message);

        // Then
        verify(transactionService, never()).processTransaction(any());
    }

    @Test
    void shouldHandleDuplicateTransactionId() {
        // Given
        String message = """
                {
                  "eventId": "evt-dup",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2026-03-26T10:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-DUPLICATE",
                    "customerId": "CUST-001",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 10.00,
                    "currency": "GBP",
                    "timestamp": "2026-03-26T10:00:00.000Z",
                    "items": []
                  }
                }
                """;

        // TransactionService throws for duplicate
        doThrow(new IllegalArgumentException("Transaction ID already exists: TXN-DUPLICATE"))
                .when(transactionService).processTransaction(any());

        // When -- should not throw
        consumer.consume(message);

        // Then -- service was called (the consumer tried), but exception was caught
        verify(transactionService, times(1)).processTransaction(any());
    }
}
```

### 6.2: Unit Test -- Field Mapping Accuracy

```java
    @Test
    void shouldMapTotalAmountCorrectlyAsBigDecimal() {
        // Given -- 7.69 as a JSON number
        String message = """
                {
                  "eventId": "evt-bd",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2026-03-26T10:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-BD-TEST",
                    "customerId": "CUST-001",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 7.69,
                    "currency": "GBP",
                    "timestamp": "2026-03-26T10:00:00.000Z",
                    "items": []
                  }
                }
                """;

        // When
        consumer.consume(message);

        // Then
        ArgumentCaptor<TransactionRequest> captor =
                ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).processTransaction(captor.capture());

        // BigDecimal.valueOf(7.69) should equal new BigDecimal("7.69")
        assertEquals(new BigDecimal("7.69"), captor.getValue().getTotalAmount());
    }

    @Test
    void shouldMapTimestampToZonedDateTime() {
        String message = """
                {
                  "eventId": "evt-ts",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2026-03-26T10:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-TS-TEST",
                    "customerId": "CUST-001",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 1.00,
                    "currency": "GBP",
                    "timestamp": "2026-03-26T14:30:00.123456+00:00",
                    "items": []
                  }
                }
                """;

        consumer.consume(message);

        ArgumentCaptor<TransactionRequest> captor =
                ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).processTransaction(captor.capture());

        assertNotNull(captor.getValue().getTimestamp());
        assertEquals(14, captor.getValue().getTimestamp().getHour());
        assertEquals(30, captor.getValue().getTimestamp().getMinute());
    }

    @Test
    void shouldMapItemsCorrectly() {
        String message = """
                {
                  "eventId": "evt-items",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2026-03-26T10:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-ITEMS-TEST",
                    "customerId": "CUST-001",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 8.48,
                    "currency": "GBP",
                    "timestamp": "2026-03-26T10:00:00.000Z",
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
                        "unitPrice": 1.16,
                        "quantity": 3,
                        "category": "Bakery"
                      }
                    ]
                  }
                }
                """;

        consumer.consume(message);

        ArgumentCaptor<TransactionRequest> captor =
                ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).processTransaction(captor.capture());

        TransactionRequest req = captor.getValue();
        assertEquals(2, req.getItems().size());

        // First item
        assertEquals("Milk", req.getItems().get(0).getProductName());
        assertEquals("MILK001", req.getItems().get(0).getProductCode());
        assertEquals(new BigDecimal("2.5"), req.getItems().get(0).getUnitPrice());
        assertEquals(2, req.getItems().get(0).getQuantity());
        assertEquals("Dairy", req.getItems().get(0).getCategory());

        // Second item
        assertEquals("Bread", req.getItems().get(1).getProductName());
        assertEquals(new BigDecimal("1.16"), req.getItems().get(1).getUnitPrice());
        assertEquals(3, req.getItems().get(1).getQuantity());
    }
```

### 6.3: Integration Test -- @EmbeddedKafka (Mention, Don't Implement)

**How it works:** `@EmbeddedKafka` starts an in-memory Kafka broker inside the JVM. You publish a message with `KafkaTemplate`, the consumer picks it up, and you verify the result in the database.

**Template from the existing codebase:** `KafkaMessageDeserializationTest.java` shows the pattern of ObjectMapper + KafkaTransactionEvent deserialization.

**What to say:**
> "For a full integration test, I would use `@EmbeddedKafka` from `spring-kafka-test` -- publish a message, let the consumer process it, then assert against the database. For the DB side, the project already has Testcontainers for PostgreSQL. In a real project with more time, I would combine `@EmbeddedKafka` with `@Testcontainers` for a fully hermetic integration test."

**Skeleton (reference only):**
```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"transactions"},
    brokerProperties = {"listeners=PLAINTEXT://localhost:9092"})
class TransactionKafkaConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void shouldConsumeAndPersistTransaction() throws Exception {
        String message = "{ ... valid event JSON ... }";
        kafkaTemplate.send("transactions", "TXN-INT-001", message);

        // Wait for async consumer processing
        Thread.sleep(3000);

        assertTrue(transactionRepository.existsByTransactionId("TXN-INT-001"));
    }
}
```

---

## Section 6B: The Dual-Format Challenge -- Two Producers, Two Schemas, One Topic

This is a DELIBERATE design choice by the interviewers. Two different sources push messages to the SAME `transactions` topic with INCOMPATIBLE formats. This will likely be the pivot point of the interview discussion.

### The Two Formats

#### Format A: Seed Data (start-all.sh) -- "Flat Format"

Pushed by `scripts/start-all.sh` via `kafka-console-producer`. 10 valid messages + 1 poison pill.

```json
{
  "transactionId": "TXN-001",
  "timestamp": "2024-06-24T10:15:30Z",
  "customerId": "CUST-12345",
  "items": [
    {
      "name": "Milk",
      "price": 2.50,
      "quantity": 1
    },
    {
      "name": "Bread",
      "price": 1.20,
      "quantity": 2
    }
  ],
  "total": 4.90,
  "paymentMethod": "card",
  "storeId": "STORE-001"
}
```

#### Format B: Python Producer (kafka_producer.py) -- "Envelope Format"

Pushed by `kafka-producer/kafka_producer.py` at 5 msg/sec. Continuous stream.

```json
{
  "eventId": "a1b2c3d4-...",
  "eventType": "TRANSACTION_CREATED",
  "eventTimestamp": "2026-03-25T14:30:00.123456+00:00",
  "source": "till-system",
  "version": "1.0",
  "data": {
    "transactionId": "TXN-A1B2C3D4",
    "customerId": "CUST-54321",
    "storeId": "STORE-003",
    "tillId": "TILL-2",
    "paymentMethod": "contactless",
    "totalAmount": 12.47,
    "currency": "GBP",
    "timestamp": "2026-03-25T14:30:00.123456+00:00",
    "items": [
      {
        "productName": "Milk",
        "productCode": "MILK001",
        "unitPrice": 2.50,
        "quantity": 1,
        "category": "Dairy"
      }
    ]
  }
}
```

#### Poison Pill (start-all.sh line 78)

```json
{
  "transactionId": "BAD-TXN",
  "timestamp": "INVALID-DATE",
  "customerId": "",
  "items": [],
  "total": "not-a-number",
  "paymentMethod": "",
  "storeId": ""
}
```

This is Format A with deliberately invalid values. It is flat (no envelope), so `KafkaTransactionEvent.data` will be null.

### Side-by-Side Comparison

#### Structural Differences

| Aspect | Format A (Seed/Flat) | Format B (Producer/Envelope) |
|--------|---------------------|------------------------------|
| **Nesting** | Flat -- all fields at root level | Envelope -- metadata at root, transaction data nested in `data` |
| **Event metadata** | NONE -- no eventId, eventType, source, version | `eventId`, `eventType`, `eventTimestamp`, `source`, `version` |
| **Transaction data location** | Root level | Inside `data` object |

#### Field Name Differences (Transaction Level)

| Concept | Format A Field | Format B Field | Java DTO Field (`TransactionRequest`) |
|---------|---------------|----------------|---------------------------------------|
| Total amount | `total` (Number) | `totalAmount` (Number) | `totalAmount` (BigDecimal) |
| Timestamp | `timestamp` (String) | `timestamp` (String, inside `data`) | `timestamp` (ZonedDateTime) |
| Till ID | **MISSING** | `tillId` (String) | `tillId` (String) |
| Currency | **MISSING** | `currency` (String) | `currency` (String, default "GBP") |

#### Field Name Differences (Item Level)

| Concept | Format A Field | Format B Field | Java DTO Field (`TransactionItemRequest`) |
|---------|---------------|----------------|------------------------------------------|
| Product name | `name` | `productName` | `productName` (String) |
| Price | `price` | `unitPrice` | `unitPrice` (BigDecimal) |
| Product code | **MISSING** | `productCode` | `productCode` (String) |
| Category | **MISSING** | `category` | `category` (String) |
| Quantity | `quantity` | `quantity` | `quantity` (Integer) -- SAME |

#### Fields Present in One But Not the Other

| Field | In Format A | In Format B | In Java DTO |
|-------|------------|------------|-------------|
| `eventId` | NO | YES | `KafkaTransactionEvent.eventId` |
| `eventType` | NO | YES | `KafkaTransactionEvent.eventType` |
| `eventTimestamp` | NO | YES | `KafkaTransactionEvent.eventTimestamp` |
| `source` | NO | YES | `KafkaTransactionEvent.source` |
| `version` | NO | YES | `KafkaTransactionEvent.version` |
| `tillId` | NO | YES | `TransactionRequest.tillId` |
| `currency` | NO | YES | `TransactionRequest.currency` |
| `productCode` | NO (items) | YES (items) | `TransactionItemRequest.productCode` |
| `category` | NO (items) | YES (items) | `TransactionItemRequest.category` |

#### Type Differences for Same-Concept Fields

| Field | Format A Type | Format B Type | Notes |
|-------|--------------|--------------|-------|
| `total` / `totalAmount` | JSON Number | JSON Number | Different field NAME, same JSON type |
| `name` / `productName` | String | String | Different field NAME |
| `price` / `unitPrice` | JSON Number | JSON Number | Different field NAME |

### What Happens When Your Consumer Receives Each Format

#### Format B (Envelope) -- This is what the consumer is built for

1. `objectMapper.readValue(message, KafkaTransactionEvent.class)` succeeds
2. `event.getData()` returns a populated `Map<String, Object>`
3. `mapToTransactionRequest(data)` extracts `totalAmount`, `timestamp`, items with `productName`, `unitPrice`, etc.
4. `transactionService.processTransaction(request)` persists successfully

#### Format A (Flat) -- This is what breaks

1. `objectMapper.readValue(message, KafkaTransactionEvent.class)` succeeds (Jackson ignores unknown fields by default)
2. `event.getEventId()` = null, `event.getEventType()` = null
3. **`event.getData()` = null** -- because the flat JSON has no `data` key
4. The null check catches it: `if (data == null) { logger.warn(...); return; }`
5. The 10 valid seed transactions from `start-all.sh` are SILENTLY DROPPED

**This means:** Your consumer processes the Python producer messages perfectly but LOSES all 11 seed messages (10 valid + 1 poison pill). The interviewer WILL notice this.

### Predicted Interview Flow

**Phase 1 -- Build the consumer (25-30 min).** You build the consumer targeting Format B (the envelope format from the Python producer). This works. The interviewer sees transactions flowing from the producer into the database.

**Phase 2 -- The pivot question.** The interviewer checks the seed data:

> "I see the transactions from the Python producer are being processed, but what about the seed data we loaded at startup? Those 10 transactions from start-all.sh -- are they in the database?"

You check. They are not. The interviewer then asks:

> "Why not? Both sources publish to the same `transactions` topic."

**Phase 3 -- The design discussion.** This opens up one of the richest Staff-level conversations:

1. **"How would you handle multiple message formats on the same topic?"**
2. **"What is the right approach -- change the consumer to handle both, or standardize the producers?"**
3. **"How does this connect to schema evolution and versioning?"**
4. **"In production at Vega, we have 50 different till vendors sending different formats. How do you solve this at scale?"**

### How to Handle It -- Four Strategies

#### Strategy 1: Format Detection in the Consumer (Pragmatic, Interview-Appropriate)

Detect which format a message uses and route to the appropriate mapper. This is the most likely expected answer during the interview.

```java
@KafkaListener(topics = "transactions", groupId = "tech-test-consumer")
public void consume(String message) {
    try {
        logger.info("Received Kafka message");

        // Parse as generic JSON tree first
        JsonNode root = objectMapper.readTree(message);

        TransactionRequest request;

        if (root.has("eventId") && root.has("data")) {
            // Format B: Envelope format from Python producer
            KafkaTransactionEvent event = objectMapper.treeToValue(root,
                    KafkaTransactionEvent.class);
            Map<String, Object> data = event.getData();
            if (data == null) {
                logger.warn("Event data is null for eventId: {}, skipping",
                        event.getEventId());
                return;
            }
            request = mapToTransactionRequest(data);
        } else if (root.has("transactionId")) {
            // Format A: Flat format from seed data / legacy producers
            request = mapFlatFormatToTransactionRequest(root);
        } else {
            logger.warn("Unrecognized message format, skipping");
            return;
        }

        transactionService.processTransaction(request);
        logger.info("Successfully processed Kafka transaction: {}",
                request.getTransactionId());

    } catch (IllegalArgumentException e) {
        logger.warn("Skipping Kafka message (validation/duplicate): {}", e.getMessage());
    } catch (Exception e) {
        logger.error("Error processing Kafka message: {}", e.getMessage(), e);
    }
}

private TransactionRequest mapFlatFormatToTransactionRequest(JsonNode root) {
    TransactionRequest request = new TransactionRequest();

    request.setTransactionId(root.path("transactionId").asText(null));
    request.setCustomerId(root.path("customerId").asText(null));
    request.setStoreId(root.path("storeId").asText(null));
    request.setPaymentMethod(root.path("paymentMethod").asText(null));
    // Format A has no tillId or currency
    request.setCurrency("GBP");

    // CRITICAL: Format A uses "total", NOT "totalAmount"
    JsonNode totalNode = root.path("total");
    if (totalNode.isNumber()) {
        request.setTotalAmount(BigDecimal.valueOf(totalNode.asDouble()));
    }

    // Timestamp
    String ts = root.path("timestamp").asText(null);
    if (ts != null) {
        try {
            request.setTimestamp(ZonedDateTime.parse(ts));
        } catch (DateTimeParseException e) {
            logger.warn("Invalid timestamp '{}' for txn {}, skipping timestamp",
                    ts, request.getTransactionId());
        }
    }

    // Items: Format A uses "name" and "price", NOT "productName" and "unitPrice"
    JsonNode itemsNode = root.path("items");
    if (itemsNode.isArray()) {
        List<TransactionItemRequest> items = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            TransactionItemRequest item = new TransactionItemRequest();
            item.setProductName(itemNode.path("name").asText(null));
            // No productCode in Format A
            if (itemNode.path("price").isNumber()) {
                item.setUnitPrice(BigDecimal.valueOf(itemNode.path("price").asDouble()));
            }
            if (itemNode.path("quantity").isNumber()) {
                item.setQuantity(itemNode.path("quantity").asInt());
            }
            // No category in Format A
            items.add(item);
        }
        request.setItems(items);
    }

    return request;
}
```

**Why `objectMapper.readTree()` first:** Parsing to a `JsonNode` tree is cheap. It lets you inspect the structure before committing to a deserialization target. If you try `readValue(message, KafkaTransactionEvent.class)` on Format A, it "succeeds" but gives you nulls everywhere -- a silent failure that is much harder to debug than an explicit format check.

**Talking point:** "I am using a discriminator pattern here -- check for the presence of `eventId` and `data` to identify the envelope format, fall back to flat format detection. In production, I would use the `version` field or a Kafka header for format routing rather than inspecting the JSON body, but for two known formats this is pragmatic and readable."

#### Strategy 2: Normalizing Adapter / Gateway (Production-Grade)

Do NOT put format detection in every consumer. Instead, put a lightweight normalizer service (or a Kafka Streams/Flink job) between the raw topic and a canonical topic.

```
Raw producers --> "transactions-raw" topic --> [Normalizer Service] --> "transactions-canonical" topic --> [Consumer]
```

The normalizer:
- Reads from `transactions-raw`
- Detects the format (envelope vs flat)
- Maps BOTH formats to a single canonical schema
- Publishes to `transactions-canonical` in the canonical format
- Sends unrecognizable messages to a dead letter topic

Consumers ONLY read from the canonical topic. They never deal with format ambiguity.

**When to mention:** "In production with multiple producer formats, I would not push format detection into every consumer. That distributes the complexity everywhere and creates a maintenance nightmare. Instead, I would introduce a normalization layer -- a lightweight Kafka Streams app or a Flink job -- that reads the raw topic, normalizes all formats to a single canonical schema, and publishes to a clean topic. Every downstream consumer works against a single, well-defined contract."

#### Strategy 3: Schema Registry with Compatibility Enforcement

Schema Registry prevents this problem from happening in the first place:
- Register a canonical JSON Schema for the `transactions` topic
- Producers MUST serialize against the registered schema
- If a producer pushes a message that does not match the schema, the serializer rejects it at the producer side

This shifts the problem LEFT -- format mismatches are caught at publish time, not consume time.

**The Schema Registry gap in this project:** There is no Schema Registry in the docker-compose. The Python producer uses plain `json.dumps()`. The seed data uses `kafka-console-producer` with raw strings. Neither validates against a schema. This is realistic -- many real-world systems have this exact gap.

#### Strategy 4: Standardize the Producers (Correct Answer, But Not Your Problem)

The real fix: make all producers agree on one format. The seed data in `start-all.sh` should use the same envelope format as `kafka_producer.py`. But in an interview, you cannot change the test infrastructure -- you deal with what exists.

**What to say:** "The root cause is a schema contract violation -- two producers are writing incompatible formats to the same topic. The correct long-term fix is producer standardization combined with Schema Registry enforcement. But in reality, you often inherit legacy producers you cannot change immediately, so the consumer must be defensive."

### Interview Questions This Generates

#### Q: "Your consumer works for the producer messages but not the seed data. Why?"

> "The Python producer wraps transaction data in an event envelope with `eventId`, `eventType`, `source`, `version`, and a `data` object. The seed data from start-all.sh is flat -- all fields at the root level, no envelope. When I deserialize the flat message into `KafkaTransactionEvent`, the `data` field is null because there is no `data` key in the JSON. My null check drops it. Additionally, the flat format uses different field names: `total` instead of `totalAmount`, `name` instead of `productName`, `price` instead of `unitPrice`. Even if I extracted the fields, the names would not match."

#### Q: "How would you make the consumer handle both formats?"

> "I would use a discriminator pattern: parse the raw JSON into a `JsonNode` tree first, check for the presence of `eventId` and `data` to identify the envelope format. If present, deserialize as `KafkaTransactionEvent` and extract from `data`. If absent but `transactionId` is present at the root, treat it as the flat format and map with the flat field names (`total`, `name`, `price`). Each format gets its own mapper method. The format detection is cheap -- one `readTree()` call and two `has()` checks."

#### Q: "Is format detection in the consumer the right approach long-term?"

> "No. It works for two known formats, but it does not scale. If a third producer starts with yet another format, every consumer needs to be updated. The right approach is a normalization layer: route all producers to a raw topic, run a normalizer that maps everything to a canonical schema, and publish to a clean topic that consumers read from. Combined with Schema Registry, you get contract enforcement at publish time and a single well-defined format at consume time."

#### Q: "How does this relate to schema evolution?"

> "This is a schema evolution problem in disguise. The flat format is effectively 'version 0' -- no metadata, no envelope, different field names. The envelope format is 'version 1.0' (it even has a `version` field). Schema Registry handles this with compatibility modes. BACKWARD compatibility means new consumers can read old data -- which is exactly what we need here. A consumer built for v1.0 (envelope) should be able to process v0 (flat) data. Without Schema Registry, you implement backward compatibility manually in the consumer, which is what the format detection approach does."

#### Q: "What about the `total` vs `totalAmount` field name difference?"

> "This is a classic breaking schema change. Renaming a field is not backward-compatible under any Schema Registry mode -- it is equivalent to deleting `total` and adding `totalAmount`. In Avro, you can handle this with aliases. In JSON Schema, there is no native alias support, so you need a mapping layer. This is exactly why the normalizer pattern exists -- it absorbs these incompatibilities in one place rather than distributing the knowledge across every consumer."

#### Q: "At Vega, we have 50 different till vendors. How do you solve this?"

> "This is a data mesh / data product problem. Each till vendor is a data source with its own format. You define a canonical transaction event schema as your 'data product contract'. Each vendor gets an adapter -- either running at the edge (sidecar pattern) or as a centralized ingestion service -- that normalizes their format to the canonical schema. The canonical topic is the data product. Consumers subscribe to the data product, never to raw vendor topics. Schema Registry enforces the canonical contract. New vendors add a new adapter but never affect existing consumers."

### Connection to the Consumer Implementation

The current consumer implementation (Section 2.3) handles Format B (envelope) and drops Format A (flat) via the `data == null` check. This is CORRECT for the initial implementation phase of the interview. The interviewer expects you to:

1. **First:** Build a working consumer for the primary format (envelope from the Python producer)
2. **Then:** Notice (or be prompted about) the seed data not being processed
3. **Then:** Discuss how you would handle both formats
4. **Optionally:** Implement Strategy 1 (format detection) if time permits

Do NOT start by trying to handle both formats. Build for one, get it working, then discuss the dual-format problem. This shows you can deliver incrementally while thinking about the bigger picture.

### Test Case for Dual-Format Support

If you implement Strategy 1, add this test:

```java
@Test
void shouldProcessFlatFormatSeedData() {
    // Given -- Format A: flat format matching start-all.sh seed data
    String message = """
            {
              "transactionId": "TXN-001",
              "timestamp": "2024-06-24T10:15:30Z",
              "customerId": "CUST-12345",
              "items": [
                {"name": "Milk", "price": 2.50, "quantity": 1},
                {"name": "Bread", "price": 1.20, "quantity": 2}
              ],
              "total": 4.90,
              "paymentMethod": "card",
              "storeId": "STORE-001"
            }
            """;

    // When
    consumer.consume(message);

    // Then
    ArgumentCaptor<TransactionRequest> captor =
            ArgumentCaptor.forClass(TransactionRequest.class);
    verify(transactionService).processTransaction(captor.capture());

    TransactionRequest req = captor.getValue();
    assertEquals("TXN-001", req.getTransactionId());
    assertEquals("CUST-12345", req.getCustomerId());
    assertEquals("STORE-001", req.getStoreId());
    assertEquals("card", req.getPaymentMethod());
    assertEquals(new BigDecimal("4.9"), req.getTotalAmount());
    assertNull(req.getTillId());  // Not present in Format A
    assertEquals("GBP", req.getCurrency());  // Default
    assertNotNull(req.getTimestamp());
    assertEquals(2, req.getItems().size());
    assertEquals("Milk", req.getItems().get(0).getProductName());  // "name" mapped to productName
    assertEquals(new BigDecimal("2.5"), req.getItems().get(0).getUnitPrice());  // "price" mapped to unitPrice
    assertNull(req.getItems().get(0).getProductCode());  // Not present in Format A
    assertNull(req.getItems().get(0).getCategory());  // Not present in Format A
}

@Test
void shouldRejectPoisonPillFromFlatFormat() {
    // Given -- BAD-TXN with "total": "not-a-number" (String, not Number)
    String message = """
            {
              "transactionId": "BAD-TXN",
              "timestamp": "INVALID-DATE",
              "customerId": "",
              "items": [],
              "total": "not-a-number",
              "paymentMethod": "",
              "storeId": ""
            }
            """;

    // When
    consumer.consume(message);

    // Then -- storeId is empty, validation should catch it
    // OR totalAmount is null because "not-a-number" is not a Number
    // Either way, processTransaction should fail or not be called with valid data
    // The exact behavior depends on TransactionService validation
}
```

---

## Section 7: Alternative Approaches (Discussion Only)

### 7.1: Approach 2 -- JsonDeserializer with Map DTO

Instead of `StringDeserializer` + manual `objectMapper.readValue()`, use Spring Kafka's `JsonDeserializer` to let the framework handle byte-to-object conversion.

**Config changes:**
```yaml
spring:
  kafka:
    consumer:
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.vega.techtest.dto"
        spring.json.value.default.type: "com.vega.techtest.dto.KafkaTransactionEvent"
```

**Consumer changes:**
```java
@KafkaListener(topics = "transactions", groupId = "tech-test-consumer")
public void consume(KafkaTransactionEvent event) {
    // No objectMapper.readValue() needed
    // But event.getData() is STILL Map<String, Object>
    TransactionRequest request = mapToTransactionRequest(event.getData());
    transactionService.processTransaction(request);
}
```

**Why `spring.json.value.default.type` is mandatory:** The Python producer uses plain `json.dumps().encode('utf-8')` (kafka_producer.py line 33). It does NOT set Spring Kafka's `__TypeId__` header. Without `default.type`, the `JsonDeserializer` throws: `IllegalStateException: No type information in headers and no default type provided`.

**The verdict:** Saves exactly 1 line of code (`objectMapper.readValue`). Does NOT eliminate the `mapToTransactionRequest()` mapping work. The `data` field is still `Map<String, Object>` regardless of which deserializer you use.

```
                          StringDeserializer          JsonDeserializer
                          ------------------          ----------------
bytes -> String              done by Kafka             (skipped)
String -> KafkaEvent         your objectMapper          done by Spring
event.getData() type         Map<String, Object>        Map<String, Object>   <-- SAME
Manual map -> Request        REQUIRED                   REQUIRED              <-- SAME
mapToItemRequest()           REQUIRED                   REQUIRED              <-- SAME
BigDecimal.valueOf()         REQUIRED                   REQUIRED              <-- SAME
ZonedDateTime.parse()        REQUIRED                   REQUIRED              <-- SAME
```

**The bottleneck is the untyped `Map<String, Object> data` field, not which deserializer you use.**

### 7.2: Approach 3 -- JsonDeserializer with Strongly-Typed DTO

The real solution: change `KafkaTransactionEvent.data` from `Map<String, Object>` to a typed `KafkaTransactionPayload` class with `BigDecimal totalAmount`, `ZonedDateTime timestamp`, and `List<KafkaTransactionItem> items`. Jackson handles all type conversion automatically via the `jackson-datatype-jsr310` module (already in build.gradle line 37).

**Consumer drops from ~80 to ~40 lines.** No unsafe `Map.get()` casts, no `instanceof` checks, no `@SuppressWarnings("unchecked")`.

**BUT -- interview trade-off:** The existing `KafkaTransactionEvent.java` is provided as starter code. Modifying it shows initiative, but also means you are changing the provided interface. Read the room. If the interviewer says "treat the DTOs as given," keep Approach 1. If they encourage refactoring, Approach 3 is the better path.

**ALSO -- format incompatibility:** The seed data in `start-all.sh` uses a flat format (no envelope), while the Python producer uses the envelope format. A strongly-typed DTO that expects the envelope will fail to deserialize the seed data flat messages into typed fields. The `Map<String, Object>` approach handles both formats because null data is caught by the null check. The test blueprint (`KafkaMessageDeserializationTest`) confirms Approach 1 is the intended approach.

**What to say:**
> "In production, I would create a strongly-typed payload DTO so Jackson handles BigDecimal and ZonedDateTime conversions automatically. That eliminates the unsafe Map casting and gives compile-time safety. Combined with JsonDeserializer, the consumer becomes very concise. But for this exercise, I am keeping the provided DTO structure and using StringDeserializer so I have full control over error handling, especially for the malformed messages on the topic."

### 7.3: Schema Registry Discussion

**What it is:** Confluent Schema Registry is a service that stores and enforces schemas (Avro, Protobuf, or JSON Schema) for Kafka topics. Producers register schemas, consumers validate against them.

**How it would change this system:**
- Producer registers a schema defining the transaction event structure, field types, and required fields
- Consumer validates incoming messages against the registered schema before deserialization
- No more untyped `Map<String, Object>` -- the schema enforces that `totalAmount` is a decimal, `timestamp` is a string, `items` is an array of objects with known fields
- Avro serialization: binary format, smaller payloads, built-in schema evolution
- JSON Schema: human-readable, similar validation, less efficient wire format

**Compatibility modes:**
| Mode | Rule | Use case |
|------|------|----------|
| `BACKWARD` | New schema can read data written by old schema | Default. Add optional fields, remove fields with defaults. |
| `FORWARD` | Old schema can read data written by new schema | Add fields with defaults, remove optional fields. |
| `FULL` | Both backward and forward compatible | Most restrictive. Best for critical financial data. |
| `NONE` | No compatibility enforcement | Dangerous in production. |

**When to mention it:**
> "In production with multiple consumers across teams, I would add Schema Registry for contract enforcement and schema evolution. Right now the producer and consumer have an implicit contract -- the JSON structure. Schema Registry makes that contract explicit. If the producer team changes the message format, backward compatibility is enforced before the change can be deployed. For this exercise it is overkill, but at Vega's scale with multiple teams consuming transaction events, it is essential."

---

## Section 8: Engineering Conversation Topics

### Scalability (Match Day Traffic)

Vega handles sports and entertainment events. Transaction volume can spike from 100 TPS to 10,000+ TPS.

- **Kafka partitioning:** The `transactions` topic has 3 partitions. For 10K TPS, increase to 12-24 partitions with matching consumer group instances. A single consumer handles ~1-3K TPS depending on processing complexity.
- **Partition key strategy:** The Python producer uses `transactionId` as the key (good distribution, no ordering guarantee per store). If per-store ordering is needed (e.g., for sequential processing of refunds), switch to `storeId` as the partition key.
- **Consumer group scaling:** Add more consumer instances with the same `group-id`. Kafka automatically rebalances partitions across instances. Maximum useful instances = number of partitions.
- **Database bottleneck:** PostgreSQL + JPA single-row inserts become the bottleneck before Kafka does. Options: batch inserts (collect N messages, single `saveAll()`), connection pooling (HikariCP is default with Spring Boot), async write buffer, or CQRS with a separate write-optimized store.
- **CQRS split:** The stats endpoint does real-time aggregation queries. At scale, pre-compute aggregates in a materialized view or separate read model updated by a stream processor.

### Error Handling and Resilience

- **Database down:** Currently the consumer catches the exception, logs it, and the offset advances -- the message is lost. In production, do NOT catch transient DB errors in the consumer method. Let them propagate so Spring Kafka does not commit the offset. The message is redelivered when the DB recovers. Distinguish transient (DB down) from permanent (invalid data) failures.
- **Dead letter topic:** `@RetryableTopic(attempts = "3", backoff = @Backoff(delay = 1000, multiplier = 2.0))` or `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` for messages that fail after N retries.
- **Circuit breaker:** Resilience4j `@CircuitBreaker` on the `processTransaction()` call. When the DB is down, the circuit opens and fails fast instead of hammering a dead database with retries.
- **Back-pressure:** `max.poll.records` (default 500) controls how many records the consumer fetches per poll. `max.poll.interval.ms` (default 5 min) is the maximum time between polls before the consumer is considered dead and rebalanced. If processing is slow, reduce `max.poll.records` to avoid hitting the interval timeout.

### Data Consistency

- **At-least-once + dedup = effectively-once:** Kafka at-least-once delivery + `existsByTransactionId` check = effectively-once processing. This is the standard financial processing pattern. Simpler and more operationally reliable than Kafka's exactly-once semantics.
- **Exactly-once in Kafka:** Requires transactional producers (`enable.idempotence=true`, `transactional.id`), and consumers with `isolation.level=read_committed`. Adds ~10-20% latency overhead and significant operational complexity. Trade-off: data correctness guarantee vs latency and ops burden.
- **Dual-write problem:** The DB write and the Kafka offset commit are separate operations. If the app crashes between them, the message is redelivered (at-least-once). The dedup check handles this. For absolute atomicity, use the transactional outbox pattern.
- **Transactional outbox:** Write transaction data AND an "outbox" event to the same database transaction. A separate process (Debezium CDC or a poller) reads the outbox and publishes to Kafka. Eliminates the dual-write problem entirely.

### Observability

- **Consumer lag:** The single most important Kafka consumer metric. `records-lag-max` growing = consumer is falling behind. Alert on sustained lag above threshold. Visible in Kafka UI at localhost:8081.
- **Micrometer metrics (production enhancement):**
  ```java
  Counter processedCounter = Counter.builder("kafka.consumer.transactions.processed")
          .description("Successfully processed Kafka transactions")
          .register(meterRegistry);
  Counter failedCounter = Counter.builder("kafka.consumer.transactions.failed")
          .description("Failed Kafka transaction processing attempts")
          .register(meterRegistry);
  Timer processingTimer = Timer.builder("kafka.consumer.processing.duration")
          .description("Time taken to process a Kafka transaction")
          .register(meterRegistry);
  ```
- **Grafana dashboard:** The project has a pre-built dashboard at `monitoring/grafana/dashboards/`. Extend with consumer lag and processing duration panels.
- **Distributed tracing:** OTel collector is already configured in docker-compose. Spring Kafka propagates trace context through Kafka headers. End-to-end tracing from producer through consumer to database.

### Testing Strategy

- **Unit tests:** Test `mapToTransactionRequest()` with various data shapes. Mock `TransactionService`. Test happy path, null data, malformed JSON, duplicate rejection.
- **@EmbeddedKafka:** Spring Kafka in-memory broker for integration tests. Publish message, verify consumer processes it, assert database state.
- **Testcontainers:** `org.testcontainers:kafka` for a real Kafka broker in Docker. Project already has `org.testcontainers:postgresql` (build.gradle line 56). Combine both for fully hermetic integration tests.
- **Error path tests:** Malformed JSON, duplicate transactionId, wrong eventType, null data, DB connection failure.

### REST-to-Kafka Migration Strategy

- **Parallel paths:** Both REST and Kafka run simultaneously. New tills use Kafka, legacy tills stay on REST. Both feed into the same `TransactionService`.
- **Shadow traffic:** Mirror REST transactions to Kafka without consuming them. Test Kafka infrastructure under real production load without risking data integrity.
- **Feature flags:** Toggle individual stores between REST and Kafka. Gradual rollout. Rollback at per-store granularity.
- **Data reconciliation:** Periodic job comparing transaction counts and totals between REST-sourced and Kafka-sourced data. Flag discrepancies.
- **Deprecation:** REST remains as a fallback during the transition. Monitor REST traffic volume. When zero, decommission the endpoint.

---

## Section 9: Time Budget

| Phase | Time | What to do |
|-------|------|------------|
| Dependency + Config | 5 min | Add `spring-kafka` to build.gradle, add Kafka block to application.yml |
| Consumer skeleton | 5 min | Create class, inject ObjectMapper + TransactionService, write consume() with try/catch |
| Data mapping | 15 min | mapToTransactionRequest(), mapToItemRequest(), BigDecimal.valueOf(), ZonedDateTime.parse() |
| Testing/verification | 10 min | Start app, check logs, hit /stats endpoint, verify poison pill handled |
| Discussion | 5-10 min | Error handling, scalability, idempotency, production improvements |

**Tips:**
- Do NOT try to add Micrometer metrics in the first pass. Get it working first, then mention metrics as a "next step."
- The data mapping is where time gets eaten. Type out the String fields first (fast), then handle totalAmount (BigDecimal.valueOf), then timestamp (ZonedDateTime.parse), then items (manual list mapping). Save items for last -- it is the most code but conceptually the same pattern.
- If you are running low on time, skip the items mapping and say: "The items mapping follows the same pattern -- iterate the list of maps and convert each field. I will stub it out and we can discuss the approach."

---

## Section 10: Follow-up Questions to Expect

### "Why StringDeserializer instead of JsonDeserializer?"

> "Three reasons. First, poison pill safety -- with StringDeserializer, a malformed message enters my try/catch and gets logged. With JsonDeserializer, deserialization fails before my listener method is called, and without ErrorHandlingDeserializer the consumer blocks on that offset forever. Second, the provided KafkaTransactionEvent has `Map<String, Object> data`, so JsonDeserializer saves me exactly one line of code -- the `objectMapper.readValue()` call -- but does not eliminate the manual mapping work. Third, it is the safer choice in a time-boxed exercise where I do not want to debug trusted packages and default type configuration."

### "What happens if the consumer crashes mid-processing?"

> "Kafka redelivers the message on the next poll because the offset was never committed. The consumer processes it again. If the transaction was already persisted before the crash, the `existsByTransactionId` check in TransactionService catches the duplicate and throws IllegalArgumentException, which the consumer catches at WARN level. This is at-least-once delivery with application-level deduplication -- the standard pattern for financial systems."

### "How would you scale this to handle 10K messages/second?"

> "First, increase topic partitions to 12-24. Then deploy multiple consumer instances in the same consumer group -- Kafka automatically distributes partitions across them. The database becomes the bottleneck before Kafka does, so I would switch to batch inserts with `saveAll()`, tune HikariCP connection pool size, and consider a CQRS split where the write path is optimized for throughput and the read path (stats queries) uses materialized views or a separate read store. Monitor consumer lag -- if it grows, add more consumers up to the partition count."

### "What about exactly-once semantics?"

> "Kafka supports exactly-once with transactional producers and `isolation.level=read_committed` on consumers. But it adds 10-20% latency overhead and significant operational complexity -- you need to manage transaction IDs, handle timeouts, and the failure modes are harder to reason about. For financial systems, the industry standard is at-least-once delivery with idempotent consumers. The `existsByTransactionId` check gives us effectively-once semantics with much simpler operations. I would only reach for Kafka exactly-once if we needed to do Kafka-to-Kafka processing with no external state."

### "How would you monitor consumer lag?"

> "Consumer lag is the difference between the latest offset on the partition and the consumer's committed offset. It is the single most important Kafka consumer metric. Spring Kafka exposes it through Micrometer as `kafka.consumer.fetch-manager-metrics.records-lag-max`. I would set up a Grafana dashboard panel and alert when sustained lag exceeds a threshold -- say, 1000 records for more than 5 minutes. The Kafka UI at localhost:8081 also shows lag per consumer group. In production, I would use Burrow or the Confluent Control Center for more sophisticated lag monitoring with rate-of-change alerting."

### "What is Schema Registry and when would you use it?"

> "Confluent Schema Registry stores versioned schemas for Kafka topics -- Avro, Protobuf, or JSON Schema. The producer registers a schema, and the consumer validates incoming messages against it. It provides contract enforcement between teams: if the producer changes the message format in a backward-incompatible way, the registry rejects the new schema. It also enables schema evolution -- you can add optional fields without breaking existing consumers. For this exercise it is overkill since we have one producer and one consumer. But at Vega's scale, with multiple teams consuming transaction events for loyalty, fraud detection, and analytics, Schema Registry is essential for preventing schema drift and runtime deserialization failures."

### "How would you handle schema evolution?"

> "Schema Registry enforces compatibility modes. BACKWARD compatibility (the default) means new consumers can read old data -- you can add optional fields or remove fields that have defaults. FORWARD compatibility means old consumers can read new data. FULL compatibility requires both. For financial events, I would use FULL compatibility to ensure no consumer breaks regardless of deployment order. In practice, this means: add new optional fields (safe), never remove required fields, never change field types. If a breaking change is needed, create a new topic version (e.g., `transactions-v2`) and migrate consumers."

### "What about back-pressure?"

> "Two Kafka consumer configs control back-pressure. `max.poll.records` (default 500) limits how many records are fetched per poll. `max.poll.interval.ms` (default 5 minutes) is the maximum time between polls before the broker considers the consumer dead and triggers a rebalance. If processing is slow -- say the DB is under load -- reduce `max.poll.records` so each poll batch finishes within the interval. If a single record takes too long, you risk a rebalance. For extreme cases, implement a pause/resume pattern: call `consumer.pause()` on assigned partitions when the processing queue is full, and `resume()` when capacity is available."

### "Why earliest for auto-offset-reset?"

> "This setting only applies on the consumer's FIRST connection to the topic, when there is no committed offset for the consumer group. `earliest` means process all existing messages from the beginning of the topic. `latest` means only process messages published after the consumer starts. Since the producer has been running and building up a backlog of transactions, we need `earliest` to process that backlog. After the first run, offsets are committed and this setting is never consulted again -- the consumer always resumes from the last committed offset."

### "What is the dead letter topic for?"

> "I noticed `start-all.sh` creates a second topic called `tech-test-topic` with 3 partitions matching the transactions topic. I believe it is pre-provisioned as a dead letter topic. When a message fails deserialization or processing after N retries, instead of dropping it, you route it to the DLT. This preserves the failed message for investigation -- an operator can inspect it, fix the issue, and republish. Spring Kafka supports this natively with `DeadLetterPublishingRecoverer`. The matching partition count means you can use the same partition key, preserving the original partition assignment."

### "How would you test this?"

> "Three levels. Unit tests: mock TransactionService, test the mapping logic with various data shapes -- valid events, null data, malformed JSON, duplicate IDs. I would test BigDecimal precision and ZonedDateTime parsing specifically. Integration tests: `@EmbeddedKafka` from `spring-kafka-test` starts an in-memory Kafka broker -- publish a message, let the consumer process it, assert the database state. For the database, the project already has Testcontainers for PostgreSQL. Contract tests: if we adopt Schema Registry, the registered schema becomes the contract test -- any producer change that breaks compatibility is rejected before deployment."

### "What if the database is down?"

> "This is the hardest edge case. Currently, the consumer catches all exceptions, logs them, and moves on -- which means the Kafka offset is committed and the message is effectively lost. In production, I would distinguish transient failures (DB down, connection timeout) from permanent failures (invalid data, duplicate ID). For transient failures, do NOT catch the exception in the consumer -- let it propagate so Spring Kafka does not commit the offset. The message is redelivered on the next poll. Add a retry backoff (`DefaultErrorHandler` with `FixedBackOff` or `ExponentialBackOff`) to avoid hammering the DB. After N retries, send to the dead letter topic. For permanent failures (like duplicate IDs), catch and skip immediately -- retrying will never succeed."

---

## Section 11: Complete Code Reference

### Final build.gradle Addition

Add after the JSON Processing block (line 38):

```groovy
// Kafka
implementation 'org.springframework.kafka:spring-kafka'
```

### Final application.yml Additions

Add under `spring:`, after the `liquibase:` block (line 27):

```yaml
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: tech-test-consumer
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      enable-auto-commit: false
    listener:
      ack-mode: record
```

### Final Consumer Class (Copy-Paste Ready)

**File:** `src/main/java/com/vega/techtest/consumer/TransactionKafkaConsumer.java`

```java
package com.vega.techtest.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.techtest.dto.KafkaTransactionEvent;
import com.vega.techtest.dto.TransactionItemRequest;
import com.vega.techtest.dto.TransactionRequest;
import com.vega.techtest.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TransactionKafkaConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TransactionKafkaConsumer.class);

    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    public TransactionKafkaConsumer(TransactionService transactionService,
                                    ObjectMapper objectMapper) {
        this.transactionService = transactionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "transactions", groupId = "tech-test-consumer")
    public void consume(String message) {
        try {
            logger.info("Received Kafka message");

            // Step 1: Deserialize the event envelope
            KafkaTransactionEvent event = objectMapper.readValue(message,
                    KafkaTransactionEvent.class);

            // Step 2: Extract and validate the data payload
            Map<String, Object> data = event.getData();
            if (data == null) {
                logger.warn("Event data is null for eventId: {}, skipping",
                        event.getEventId());
                return;
            }

            // Step 3: Map untyped data to TransactionRequest
            TransactionRequest request = mapToTransactionRequest(data);

            // Step 4: Process (idempotency handled by TransactionService)
            transactionService.processTransaction(request);

            logger.info("Successfully processed Kafka transaction: {}",
                    request.getTransactionId());

        } catch (IllegalArgumentException e) {
            // Duplicate transactionId or validation failure -- deterministic, do NOT retry
            logger.warn("Skipping Kafka message (validation/duplicate): {}", e.getMessage());
        } catch (Exception e) {
            // Malformed JSON, mapping error, unexpected failure
            // Log and move on -- do NOT rethrow or the consumer retries forever
            logger.error("Error processing Kafka message: {}", e.getMessage(), e);
        }
    }

    private TransactionRequest mapToTransactionRequest(Map<String, Object> data) {
        TransactionRequest request = new TransactionRequest();

        request.setTransactionId((String) data.get("transactionId"));
        request.setCustomerId((String) data.get("customerId"));
        request.setStoreId((String) data.get("storeId"));
        request.setTillId((String) data.get("tillId"));
        request.setPaymentMethod((String) data.get("paymentMethod"));
        request.setCurrency((String) data.getOrDefault("currency", "GBP"));

        // CRITICAL: totalAmount comes as Double from JSON
        // Use BigDecimal.valueOf(double) -- NOT new BigDecimal(double)
        Object totalAmountObj = data.get("totalAmount");
        if (totalAmountObj instanceof Number number) {
            request.setTotalAmount(BigDecimal.valueOf(number.doubleValue()));
        }

        // CRITICAL: timestamp comes as ISO String, not ZonedDateTime
        Object timestampObj = data.get("timestamp");
        if (timestampObj instanceof String ts) {
            request.setTimestamp(ZonedDateTime.parse(ts));
        }

        // Items come as List<LinkedHashMap> -- manual conversion needed
        Object itemsObj = data.get("items");
        if (itemsObj instanceof List<?> rawItems) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> itemMaps = (List<Map<String, Object>>) rawItems;
            List<TransactionItemRequest> items = itemMaps.stream()
                    .map(this::mapToItemRequest)
                    .collect(Collectors.toList());
            request.setItems(items);
        }

        return request;
    }

    private TransactionItemRequest mapToItemRequest(Map<String, Object> itemMap) {
        TransactionItemRequest item = new TransactionItemRequest();

        item.setProductName((String) itemMap.get("productName"));
        item.setProductCode((String) itemMap.get("productCode"));
        item.setCategory((String) itemMap.get("category"));

        Object priceObj = itemMap.get("unitPrice");
        if (priceObj instanceof Number number) {
            item.setUnitPrice(BigDecimal.valueOf(number.doubleValue()));
        }

        Object qtyObj = itemMap.get("quantity");
        if (qtyObj instanceof Number number) {
            item.setQuantity(number.intValue());
        }

        return item;
    }
}
```

### Final Test Class (Copy-Paste Ready)

**File:** `src/test/java/com/vega/techtest/consumer/TransactionKafkaConsumerTest.java`

```java
package com.vega.techtest.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.techtest.dto.TransactionRequest;
import com.vega.techtest.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionKafkaConsumerTest {

    @Mock
    private TransactionService transactionService;

    private TransactionKafkaConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new TransactionKafkaConsumer(transactionService, objectMapper);
    }

    @Test
    void shouldProcessValidKafkaMessage() {
        String message = """
                {
                  "eventId": "test-event-1",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2026-03-26T10:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-TEST001",
                    "customerId": "CUST-001",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 7.69,
                    "currency": "GBP",
                    "timestamp": "2026-03-26T10:00:00.000Z",
                    "items": [
                      {
                        "productName": "Milk",
                        "productCode": "MILK001",
                        "unitPrice": 2.50,
                        "quantity": 1,
                        "category": "Dairy"
                      },
                      {
                        "productName": "Bread",
                        "productCode": "BREAD001",
                        "unitPrice": 1.73,
                        "quantity": 3,
                        "category": "Bakery"
                      }
                    ]
                  }
                }
                """;

        consumer.consume(message);

        ArgumentCaptor<TransactionRequest> captor =
                ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService, times(1)).processTransaction(captor.capture());

        TransactionRequest captured = captor.getValue();
        assertEquals("TXN-TEST001", captured.getTransactionId());
        assertEquals("CUST-001", captured.getCustomerId());
        assertEquals("STORE-001", captured.getStoreId());
        assertEquals("TILL-1", captured.getTillId());
        assertEquals("card", captured.getPaymentMethod());
        assertEquals("GBP", captured.getCurrency());
        assertEquals(new BigDecimal("7.69"), captured.getTotalAmount());
        assertNotNull(captured.getTimestamp());
        assertEquals(2, captured.getItems().size());
        assertEquals("Milk", captured.getItems().get(0).getProductName());
        assertEquals(new BigDecimal("2.5"), captured.getItems().get(0).getUnitPrice());
        assertEquals(1, captured.getItems().get(0).getQuantity());
    }

    @Test
    void shouldHandleNullDataGracefully() {
        // Flat JSON -- no event envelope (matches the BAD-TXN poison pill format)
        String message = """
                {
                  "transactionId": "BAD-TXN",
                  "timestamp": "INVALID-DATE",
                  "customerId": "",
                  "items": [],
                  "total": "not-a-number"
                }
                """;

        consumer.consume(message);

        verify(transactionService, never()).processTransaction(any());
    }

    @Test
    void shouldHandleMalformedJsonGracefully() {
        String message = "this is not json at all {{{";

        consumer.consume(message);

        verify(transactionService, never()).processTransaction(any());
    }

    @Test
    void shouldHandleDuplicateTransactionId() {
        String message = """
                {
                  "eventId": "evt-dup",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2026-03-26T10:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-DUPLICATE",
                    "customerId": "CUST-001",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 10.00,
                    "currency": "GBP",
                    "timestamp": "2026-03-26T10:00:00.000Z",
                    "items": []
                  }
                }
                """;

        doThrow(new IllegalArgumentException("Transaction ID already exists: TXN-DUPLICATE"))
                .when(transactionService).processTransaction(any());

        consumer.consume(message);

        verify(transactionService, times(1)).processTransaction(any());
    }

    @Test
    void shouldMapTotalAmountWithBigDecimalPrecision() {
        String message = """
                {
                  "eventId": "evt-bd",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2026-03-26T10:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-PRECISION",
                    "customerId": "CUST-001",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 7.69,
                    "currency": "GBP",
                    "timestamp": "2026-03-26T10:00:00.000Z",
                    "items": []
                  }
                }
                """;

        consumer.consume(message);

        ArgumentCaptor<TransactionRequest> captor =
                ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).processTransaction(captor.capture());

        // This MUST equal "7.69", not "7.6900000000000003552713678800..."
        assertEquals(new BigDecimal("7.69"), captor.getValue().getTotalAmount());
    }

    @Test
    void shouldMapItemFieldsCorrectly() {
        String message = """
                {
                  "eventId": "evt-items",
                  "eventType": "TRANSACTION_CREATED",
                  "eventTimestamp": "2026-03-26T10:00:00.000Z",
                  "source": "till-system",
                  "version": "1.0",
                  "data": {
                    "transactionId": "TXN-ITEMS",
                    "customerId": "CUST-001",
                    "storeId": "STORE-001",
                    "tillId": "TILL-1",
                    "paymentMethod": "card",
                    "totalAmount": 8.48,
                    "currency": "GBP",
                    "timestamp": "2026-03-26T10:00:00.000Z",
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
                        "unitPrice": 1.16,
                        "quantity": 3,
                        "category": "Bakery"
                      }
                    ]
                  }
                }
                """;

        consumer.consume(message);

        ArgumentCaptor<TransactionRequest> captor =
                ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).processTransaction(captor.capture());

        TransactionRequest req = captor.getValue();
        assertEquals(2, req.getItems().size());

        assertEquals("Milk", req.getItems().get(0).getProductName());
        assertEquals("MILK001", req.getItems().get(0).getProductCode());
        assertEquals(new BigDecimal("2.5"), req.getItems().get(0).getUnitPrice());
        assertEquals(2, req.getItems().get(0).getQuantity());
        assertEquals("Dairy", req.getItems().get(0).getCategory());

        assertEquals("Bread", req.getItems().get(1).getProductName());
        assertEquals(new BigDecimal("1.16"), req.getItems().get(1).getUnitPrice());
        assertEquals(3, req.getItems().get(1).getQuantity());
        assertEquals("Bakery", req.getItems().get(1).getCategory());
    }
}
```
