# Kafka Consumer

**Time budget:** 40-50 min for implementation + error handling + metrics.
**This is the senior-level gate.** Building this end-to-end is what separates mid from senior.

---

## Architecture Overview

### What Exists Today

```
PATH 1 (REST -- Working, with bugs):
  Till Simulator (Python) --POST--> TransactionController --> TransactionService --> PostgreSQL

PATH 2 (Kafka -- BROKEN by design, no consumer exists):
  Kafka Producer (Python) --publish--> 'transactions' topic --> ??? (nothing consuming)
```

**Already provided:**
1. `kafka-producer/kafka_producer.py` -- publishes 5 messages/sec to `transactions` topic
2. `KafkaTransactionEvent` DTO -- event envelope with `Map<String, Object> data` (line 30)
3. `KafkaMessageDeserializationTest` -- shows Jackson deserialization pattern
4. `TransactionService.processTransaction(TransactionRequest)` -- handles persistence + dedup

**What you build:**
A `@KafkaListener` class that consumes, deserializes, maps, and persists Kafka transactions.

---

## Step 1: Add spring-kafka dependency

**File:** `build.gradle` (add in the dependencies block, around line 29)

```groovy
// Kafka
implementation 'org.springframework.kafka:spring-kafka'

// (Optional) For @EmbeddedKafka tests
testImplementation 'org.springframework.kafka:spring-kafka-test'
```

Spring Boot managed dependency -- no version number needed (BOM handles it).

**Note:** `spring-kafka` is deliberately NOT in the existing `build.gradle`. This is by design because the consumer does not exist yet.

---

## Step 2: Add Kafka consumer config to application.yml

**File:** `src/main/resources/application.yml` (add under `spring:`, after the `liquibase:` block at line 27)

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

### Config Rationale

| Setting | Value | Why |
|---------|-------|-----|
| `bootstrap-servers` | `localhost:9092` | App runs on host, not in Docker. Docker internal is `kafka:29092` |
| `auto-offset-reset` | `earliest` | Process all messages from beginning on first start. If `latest`, miss the backlog |
| `key-deserializer` | `StringDeserializer` | Producer uses transactionId (String) as key |
| `value-deserializer` | `StringDeserializer` | Deserialize as raw String, then parse JSON manually (safer than JsonDeserializer for handling malformed messages) |
| `enable-auto-commit` | `false` | Manual ack after successful processing |
| `ack-mode` | `record` | Ack each record individually (at-least-once guarantee) |

---

## Step 3: Create the Kafka Consumer class

**New file:** `src/main/java/com/vega/techtest/consumer/TransactionKafkaConsumer.java`

### Minimal Version (get it working first)

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

            // Step 2: Validate event type
            if (!"TRANSACTION_CREATED".equals(event.getEventType())) {
                logger.warn("Ignoring event with type: {}", event.getEventType());
                return;
            }

            // Step 3: Extract data map
            Map<String, Object> data = event.getData();
            if (data == null) {
                logger.error("Event data is null for eventId: {}", event.getEventId());
                return;
            }

            // Step 4: Map to TransactionRequest
            TransactionRequest request = mapToTransactionRequest(data);

            // Step 5: Process (idempotency handled by TransactionService)
            transactionService.processTransaction(request);

            logger.info("Successfully processed Kafka transaction: {}",
                    request.getTransactionId());

        } catch (IllegalArgumentException e) {
            // Duplicate transactionId or validation failure -- log and skip
            logger.warn("Skipping Kafka message (validation): {}", e.getMessage());
        } catch (Exception e) {
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

        // Items come as List<Map<String, Object>> -- manual conversion needed
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

### Enhanced Version (add Micrometer metrics after basic version works)

Add these fields and constructor changes:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

// Additional fields
private final Counter processedCounter;
private final Counter failedCounter;
private final Timer processingTimer;

// Updated constructor
public TransactionKafkaConsumer(TransactionService transactionService,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
    this.transactionService = transactionService;
    this.objectMapper = objectMapper;

    this.processedCounter = Counter.builder("kafka.consumer.transactions.processed")
            .description("Successfully processed Kafka transactions")
            .register(meterRegistry);

    this.failedCounter = Counter.builder("kafka.consumer.transactions.failed")
            .description("Failed Kafka transaction processing attempts")
            .register(meterRegistry);

    this.processingTimer = Timer.builder("kafka.consumer.processing.duration")
            .description("Time taken to process a Kafka transaction message")
            .register(meterRegistry);
}

// Updated consume method
@KafkaListener(topics = "transactions", groupId = "tech-test-consumer")
public void consume(String message) {
    Timer.Sample sample = Timer.start();

    try {
        // ... same logic as above ...
        processedCounter.increment();
    } catch (IllegalArgumentException e) {
        logger.warn("Skipping Kafka message (validation): {}", e.getMessage());
        // Don't increment failed counter for expected duplicates
    } catch (Exception e) {
        failedCounter.increment();
        logger.error("Error processing Kafka message: {}", e.getMessage(), e);
    } finally {
        sample.stop(processingTimer);
    }
}
```

---

## Data Mapping Gotchas (Critical)

These are the type-mismatch traps in the untyped `Map<String, Object>`:

| Field | Kafka JSON Type | Java DTO Type | Conversion | Trap |
|-------|----------------|---------------|------------|------|
| `totalAmount` | Double (25.50) | BigDecimal | `BigDecimal.valueOf(double)` | `new BigDecimal(double)` introduces IEEE 754 noise |
| `timestamp` | String (ISO 8601) | ZonedDateTime | `ZonedDateTime.parse(string)` | Cannot cast String to ZonedDateTime |
| `items` | `List<LinkedHashMap>` | `List<TransactionItemRequest>` | Manual field-by-field | `ClassCastException` if you try direct cast |
| `unitPrice` | Double | BigDecimal | `BigDecimal.valueOf(double)` | Same as totalAmount |
| `quantity` | Integer | Integer | `((Number) obj).intValue()` | Could be Long in some edge cases |

### Why BigDecimal.valueOf(double) not new BigDecimal(double)

```java
new BigDecimal(25.50)     // = 25.500000000000000... (IEEE 754 representation)
BigDecimal.valueOf(25.50) // = 25.5 (goes through Double.toString() first)
```

In financial systems, `new BigDecimal(double)` is a well-known anti-pattern.

### Why KafkaTransactionEvent.data is Map<String, Object>

`KafkaTransactionEvent.java` line 30:
```java
@JsonProperty("data")
private Map<String, Object> data;
```

Deliberately untyped. Jackson deserializes JSON numbers as `Double` and JSON arrays as `ArrayList<LinkedHashMap>`. You CANNOT simply cast `data.get("items")` to `List<TransactionItemRequest>` -- it will throw `ClassCastException`.

---

## Kafka Message Format (from Producer)

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "TRANSACTION_CREATED",
  "eventTimestamp": "2026-03-26T10:00:00.000000+00:00",
  "source": "till-system",
  "version": "1.0",
  "data": {
    "transactionId": "TXN-A1B2C3D4",
    "customerId": "CUST-54321",
    "storeId": "STORE-003",
    "tillId": "TILL-5",
    "paymentMethod": "contactless",
    "totalAmount": 12.48,
    "currency": "GBP",
    "timestamp": "2026-03-26T10:00:00.000000+00:00",
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

Key differences from REST TransactionRequest:
- Wrapped in event envelope (eventId, eventType, eventTimestamp, source, version)
- `data.totalAmount` is a JSON number (Double), not BigDecimal
- `data.timestamp` is an ISO string, not ZonedDateTime
- `data.items[].unitPrice` is a JSON number (Double)
- The `data` field in the Java DTO is `Map<String, Object>` -- fully untyped

---

## Docker Networking

From `docker-compose.yml` line 41:
```yaml
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,PLAINTEXT_HOST://kafka:29092
```

| Context | Address | When |
|---------|---------|------|
| **From host** (where `./gradlew bootRun` runs) | `localhost:9092` | Your application.yml |
| **From inside Docker** (kafka-producer container) | `kafka:29092` | Docker-internal only |

Using the wrong address causes `org.apache.kafka.common.errors.TimeoutException`.

---

## Idempotency

`TransactionService.processTransaction()` already checks for duplicates at line 50:
```java
if (transactionRepository.existsByTransactionId(transactionId)) {
    throw new IllegalArgumentException("Transaction ID already exists: " + transactionId);
}
```

The consumer catches `IllegalArgumentException` and logs a warning -- this handles at-least-once delivery where a message may be redelivered after consumer restart.

---

## Error Handling Strategy

### Current Implementation

```
try {
    deserialize -> validate event type -> extract data -> map -> process
} catch (IllegalArgumentException) {
    // Duplicate transactionId or validation failure
    // Log WARN and skip -- do NOT retry (deterministic failure)
} catch (Exception) {
    // Malformed JSON, mapping error, DB down, etc.
    // Log ERROR -- message is acknowledged (lost if not retried)
    // For production: send to dead-letter topic
}
```

### Interview Discussion Points

1. **Poison pill messages:** A malformed JSON message causes deserialization exception. If you rethrow, the consumer retries forever. Solution: catch, log, and skip (or DLQ).

2. **Dead-letter queue (DLQ):** Spring Kafka `@RetryableTopic` with automatic DLQ routing:
   ```java
   @RetryableTopic(
       attempts = "3",
       backoff = @Backoff(delay = 1000, multiplier = 2.0),
       dltTopicSuffix = ".DLT"
   )
   ```
   Mention as a production enhancement.

3. **Database down:** If PostgreSQL is unavailable, `processTransaction()` throws. The message should NOT be acknowledged so Kafka redelivers when DB recovers. Current implementation acks the message even on DB failure -- in production, you would distinguish transient vs permanent failures.

4. **Back-pressure:** Monitor consumer lag via Micrometer `kafka.consumer.fetch-manager-metrics.records-lag-max`. If lag grows, scale consumer instances.

5. **Poison pill from start-all.sh:** The script publishes a deliberately malformed message:
   ```json
   {"transactionId":"BAD-TXN","timestamp":"INVALID-DATE","customerId":"","items":[],"total":"not-a-number"}
   ```
   Your error handling must gracefully skip this.

---

## Consumer Group Semantics

- **Consumer group `tech-test-consumer`:** All instances share the same group ID
- **Partition assignment:** The `transactions` topic has 3 partitions (created by `start-all.sh`). A single consumer gets all 3 partitions. Adding more consumer instances (up to 3) distributes partitions.
- **Scaling:** More partitions = more parallelism. For 10K TPS, consider 12-24 partitions with matching consumer instances.
- **Partition key:** The producer uses `transactionId` as the key (good distribution, no per-store ordering). If per-store ordering is needed, use `storeId` as key.

---

## auto-offset-reset: earliest Rationale

- `earliest`: Process all messages from beginning on first consumer start. Critical because the producer has been running and building up a backlog.
- `latest`: Only process messages published AFTER the consumer starts. Loses all backlog.
- This setting only applies when there is NO committed offset for the consumer group. After the first successful consumption, offsets are committed and this setting is ignored.

---

## Testing Approach

### Unit Test for Mapping Logic

Test `mapToTransactionRequest()` with various data shapes. The existing `KafkaMessageDeserializationTest` is a template.

```java
@Test
void shouldMapKafkaEventToTransactionRequest() {
    // Given
    Map<String, Object> data = Map.of(
        "transactionId", "TXN-TEST001",
        "customerId", "CUST-001",
        "storeId", "STORE-001",
        "tillId", "TILL-1",
        "paymentMethod", "card",
        "totalAmount", 7.69,
        "currency", "GBP",
        "timestamp", "2026-03-26T10:00:00.000+00:00",
        "items", List.of(Map.of(
            "productName", "Milk",
            "productCode", "MILK001",
            "unitPrice", 2.50,
            "quantity", 1,
            "category", "Dairy"
        ))
    );

    // When
    TransactionRequest request = consumer.mapToTransactionRequest(data);

    // Then
    assertEquals("TXN-TEST001", request.getTransactionId());
    assertEquals(new BigDecimal("7.69"), request.getTotalAmount());
    assertEquals(1, request.getItems().size());
}
```

### Integration Test with @EmbeddedKafka

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"transactions"})
class TransactionKafkaConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void shouldConsumeAndPersistTransaction() {
        // Publish a message, then verify it was persisted
    }
}
```

### Error Path Tests

- Publish malformed JSON -- verify consumer does not crash
- Publish duplicate transactionId -- verify idempotency (logged, not thrown)
- Publish event with wrong eventType -- verify it is skipped

---

## Engineering Conversation Topics

These are the topics interviewers will probe during or after coding. Prepare 2-3 sentences on each.

### Scalability (Match Day Traffic)

Vega handles sports/entertainment events. Transaction volume could spike from 100 TPS to 10,000+ TPS.

- **Kafka partitioning:** Topic has 3 partitions. For 10K TPS, need 12-24 partitions + matching consumer group parallelism.
- **Partition key strategy:** Producer uses `transactionId` (good distribution, no ordering). Consider `storeId` for per-store ordering.
- **CQRS:** Separate write path (consumer persists) from read path (stats queries). Stats endpoint does real-time aggregation -- at scale, pre-compute aggregates or use materialized views.
- **Database bottleneck:** PostgreSQL + JPA becomes a bottleneck at high TPS. Options: batch inserts, connection pooling (HikariCP default), async write buffer.

### Error Handling and Resilience

- **Database down:** Do not ack the Kafka offset. Consumer pauses, Kafka redelivers when DB recovers.
- **Dead-letter topic:** `@RetryableTopic` with N retries + DLQ for manual investigation.
- **Circuit breaker:** Resilience4j prevents hammering a dead database.
- **Back-pressure:** Monitor `records-lag-max`. If growing, scale consumers.
- **Poison pill:** A single malformed message must never block the entire partition.

### Data Consistency

- **At-least-once + dedup = effectively-once:** Kafka at-least-once + `existsByTransactionId` = effectively-once. Standard financial pattern.
- **Transactional outbox:** If you need atomic event+DB write, use outbox pattern (write to outbox table, separate process tails and publishes).
- **Exactly-once in Kafka:** Transactional producers + `isolation.level=read_committed`. Trade-off: higher latency, more complex config.
- **Dual-write problem:** DB write and Kafka ack are separate. App crash between them = redelivery, hence idempotency check.

### Observability

- **Consumer lag:** The single most important metric. `records-lag-max` growing = falling behind.
- **Custom Micrometer metrics:**
  - `kafka.consumer.transactions.processed.total` (counter)
  - `kafka.consumer.transactions.failed.total` (counter)
  - `kafka.consumer.processing.duration` (timer)
- **Grafana dashboard:** Project has a pre-built dashboard at `monitoring/grafana/dashboards/`. Extend with consumer metrics.
- **Distributed tracing:** OTel collector is already configured. Spring Kafka propagates trace context through Kafka headers for end-to-end tracing.

### Testing Strategy

- **Unit tests:** Test `mapToTransactionRequest()` with various data shapes. Existing `KafkaMessageDeserializationTest` is a template.
- **@EmbeddedKafka:** Spring Kafka in-memory broker for integration tests.
- **Testcontainers:** `org.testcontainers:kafka` for realistic tests. Project already has Testcontainers for PostgreSQL (`build.gradle` line 56).
- **Error path tests:** Malformed JSON, duplicate transactionId, wrong eventType.

### REST-to-Kafka Migration Strategy

- **Parallel paths:** Both REST and Kafka simultaneously. New tills use Kafka, legacy on REST. Both feed into same TransactionService.
- **Shadow traffic:** Mirror REST transactions to Kafka without consuming -- test infrastructure under real load.
- **Feature flags:** Toggle individual stores between REST and Kafka. Gradual rollout.
- **Data reconciliation:** Periodic job comparing counts/totals between REST and Kafka paths.
- **Deprecation:** REST remains during transition. Monitor traffic. When zero, decommission.

---

## Verification Commands

After implementing the consumer:

```bash
# 1. Start infrastructure
docker-compose up -d

# 2. Build and run
./gradlew bootRun

# 3. Health check
curl http://localhost:8080/api/transactions/health

# 4. Stats (should see increasing counts from Kafka-ingested data)
curl http://localhost:8080/api/transactions/stats/STORE-001

# 5. Store that only gets Kafka traffic
curl http://localhost:8080/api/transactions/store/STORE-003

# 6. Kafka UI -- see consumer group lag
# http://localhost:8081

# 7. Consumer group offsets
docker exec tech-test-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group tech-test-consumer

# 8. Prometheus metrics
curl http://localhost:8080/actuator/prometheus | grep kafka.consumer
```

---

## Smart Questions to Ask the Interviewers

- "What is the expected transaction volume? Designing for steady state or burst like match day?"
- "Preference for exactly-once vs at-least-once? Current approach is at-least-once with dedup."
- "How does the team handle schema evolution for Kafka messages? Is a schema registry in the roadmap?"
- "Is the consumer a separate service or part of this monolith? That affects scaling strategy."
- "Data retention policy for the transactions table? At 5 TPS, that is ~15 million rows per year."

---

## Appendix: JsonDeserializer vs StringDeserializer -- Full Trade-off Analysis

### The Question

> "Can we not use `JsonDeserializer` in the Kafka consumer? This way we don't need to use `ObjectMapper` to read value from string to Java object."

Short answer: **Yes, you can, and it is arguably the more Spring-idiomatic approach.** But it does NOT eliminate the mapping problem. Here is the full analysis.

---

### Understanding the Two Approaches

#### Approach 1 (Current): StringDeserializer + Manual ObjectMapper

```yaml
# application.yml
spring:
  kafka:
    consumer:
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

```java
@KafkaListener(topics = "transactions", groupId = "tech-test-consumer")
public void consume(String message) {
    KafkaTransactionEvent event = objectMapper.readValue(message, KafkaTransactionEvent.class);
    // event.getData() is still Map<String, Object> -- manual mapping required
    TransactionRequest request = mapToTransactionRequest(event.getData());
    transactionService.processTransaction(request);
}
```

**What happens at runtime:**
1. Kafka delivers raw bytes from the topic
2. `StringDeserializer` converts bytes to `String` (UTF-8 decode)
3. Your code calls `objectMapper.readValue(string, KafkaTransactionEvent.class)`
4. Jackson parses JSON string into `KafkaTransactionEvent`
5. `data` field becomes `Map<String, Object>` (Jackson default for untyped maps)
6. You manually convert map entries to `TransactionRequest` fields

#### Approach 2 (Suggested): Spring Kafka JsonDeserializer

```yaml
# application.yml
spring:
  kafka:
    consumer:
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.vega.techtest.dto"
        spring.json.value.default.type: "com.vega.techtest.dto.KafkaTransactionEvent"
```

```java
@KafkaListener(topics = "transactions", groupId = "tech-test-consumer")
public void consume(KafkaTransactionEvent event) {
    // No objectMapper.readValue() needed -- event is already deserialized
    // But event.getData() is STILL Map<String, Object>
    TransactionRequest request = mapToTransactionRequest(event.getData());
    transactionService.processTransaction(request);
}
```

**What happens at runtime:**
1. Kafka delivers raw bytes from the topic
2. `JsonDeserializer` converts bytes directly to `KafkaTransactionEvent` (uses Jackson internally)
3. `data` field becomes `Map<String, Object>` (same as above -- Jackson default)
4. You STILL manually convert map entries to `TransactionRequest` fields

---

### Does JsonDeserializer Actually Solve the Problem?

**No, not fully.** Here is why:

The real pain in the current consumer is NOT the `objectMapper.readValue()` line. It is the 50+ lines of `mapToTransactionRequest()` and `mapToItemRequest()` that manually extract `Double` to `BigDecimal`, `String` to `ZonedDateTime`, and `List<LinkedHashMap>` to `List<TransactionItemRequest>`.

`JsonDeserializer` saves you exactly **one line of code** (the `objectMapper.readValue` call). The `data` field is `Map<String, Object>` in the DTO -- Jackson has no type information to do anything smarter with it, regardless of whether it is called from your code or from Spring's deserializer.

```
                          StringDeserializer          JsonDeserializer
                          ──────────────────          ────────────────
bytes -> String              done by Kafka             (skipped)
String -> KafkaEvent         your objectMapper          done by Spring
event.getData() type         Map<String, Object>        Map<String, Object>  <-- SAME
Manual map -> Request        REQUIRED                   REQUIRED             <-- SAME
mapToItemRequest()           REQUIRED                   REQUIRED             <-- SAME
BigDecimal.valueOf()         REQUIRED                   REQUIRED             <-- SAME
ZonedDateTime.parse()        REQUIRED                   REQUIRED             <-- SAME
```

**The bottleneck is the untyped `Map<String, Object> data` in `KafkaTransactionEvent.java`, not which deserializer you use.**

---

### The REAL Solution: Approach 3 -- Strongly-Typed Event DTO

If you want to eliminate the manual mapping entirely, **change the `data` field from `Map<String, Object>` to a strongly-typed class**. Then Jackson (whether invoked by you or by Spring's `JsonDeserializer`) handles all type conversion automatically.

#### Step 1: Create a typed payload class

```java
package com.vega.techtest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Strongly-typed representation of the transaction data payload
 * inside the Kafka event envelope. Eliminates manual Map extraction.
 */
public class KafkaTransactionPayload {

    @JsonProperty("transactionId")
    private String transactionId;

    @JsonProperty("customerId")
    private String customerId;

    @JsonProperty("storeId")
    private String storeId;

    @JsonProperty("tillId")
    private String tillId;

    @JsonProperty("paymentMethod")
    private String paymentMethod;

    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("timestamp")
    private ZonedDateTime timestamp;

    @JsonProperty("items")
    private List<KafkaTransactionItem> items;

    // Default constructor for Jackson
    public KafkaTransactionPayload() {}

    // Getters and setters
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }

    public String getTillId() { return tillId; }
    public void setTillId(String tillId) { this.tillId = tillId; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public ZonedDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(ZonedDateTime timestamp) { this.timestamp = timestamp; }

    public List<KafkaTransactionItem> getItems() { return items; }
    public void setItems(List<KafkaTransactionItem> items) { this.items = items; }
}
```

#### Step 2: Create a typed item class

```java
package com.vega.techtest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public class KafkaTransactionItem {

    @JsonProperty("productName")
    private String productName;

    @JsonProperty("productCode")
    private String productCode;

    @JsonProperty("unitPrice")
    private BigDecimal unitPrice;

    @JsonProperty("quantity")
    private Integer quantity;

    @JsonProperty("category")
    private String category;

    public KafkaTransactionItem() {}

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
```

#### Step 3: Modify KafkaTransactionEvent to use the typed payload

```java
// CHANGE this:
@JsonProperty("data")
private Map<String, Object> data;

// TO this:
@JsonProperty("data")
private KafkaTransactionPayload data;
```

**WARNING -- interview trade-off:** The existing `KafkaTransactionEvent.java` is provided by Vega as starter code. Modifying it shows initiative and senior-level thinking, but also shows you are willing to change provided interfaces. Read the room -- if the interviewer says "treat the DTOs as given", keep the `Map<String, Object>` approach. If they encourage refactoring, this is the better path.

#### Step 4: The consumer becomes trivially simple

**With JsonDeserializer + strongly-typed DTO:**

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: tech-test-consumer
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      enable-auto-commit: false
      properties:
        spring.json.trusted.packages: "com.vega.techtest.dto"
        spring.json.value.default.type: "com.vega.techtest.dto.KafkaTransactionEvent"
    listener:
      ack-mode: record
```

```java
@Component
public class TransactionKafkaConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TransactionKafkaConsumer.class);

    private final TransactionService transactionService;

    public TransactionKafkaConsumer(TransactionService transactionService) {
        this.transactionService = transactionService;
        // No ObjectMapper needed!
    }

    @KafkaListener(topics = "transactions", groupId = "tech-test-consumer")
    public void consume(KafkaTransactionEvent event) {
        try {
            logger.info("Received Kafka event: {}", event.getEventId());

            if (!"TRANSACTION_CREATED".equals(event.getEventType())) {
                logger.warn("Ignoring event with type: {}", event.getEventType());
                return;
            }

            KafkaTransactionPayload data = event.getData();
            if (data == null) {
                logger.error("Event data is null for eventId: {}", event.getEventId());
                return;
            }

            // Direct mapping -- no manual type conversion!
            TransactionRequest request = new TransactionRequest();
            request.setTransactionId(data.getTransactionId());
            request.setCustomerId(data.getCustomerId());
            request.setStoreId(data.getStoreId());
            request.setTillId(data.getTillId());
            request.setPaymentMethod(data.getPaymentMethod());
            request.setTotalAmount(data.getTotalAmount());       // Already BigDecimal
            request.setCurrency(data.getCurrency());
            request.setTimestamp(data.getTimestamp());            // Already ZonedDateTime
            request.setItems(data.getItems().stream()
                    .map(item -> new TransactionItemRequest(
                            item.getProductName(),
                            item.getProductCode(),
                            item.getUnitPrice(),                 // Already BigDecimal
                            item.getQuantity(),
                            item.getCategory()
                    ))
                    .collect(Collectors.toList()));

            transactionService.processTransaction(request);
            logger.info("Successfully processed Kafka transaction: {}", data.getTransactionId());

        } catch (IllegalArgumentException e) {
            logger.warn("Skipping Kafka message (validation): {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Error processing Kafka message: {}", e.getMessage(), e);
        }
    }
}
```

**What disappeared:**
- `ObjectMapper` injection and `readValue()` call
- `mapToTransactionRequest()` with unsafe `Map.get()` casts
- `mapToItemRequest()` with `Number` to `BigDecimal` conversions
- `instanceof` checks for every field
- `@SuppressWarnings("unchecked")` for the items list

---

### Critical: Why `spring.json.value.default.type` Is Mandatory Here

The Python producer (line 31-34 of `kafka_producer.py`) uses:
```python
value_serializer=lambda v: json.dumps(v).encode('utf-8')
```

This is plain JSON serialization. It does **NOT** set Spring Kafka's `__TypeId__` header on the Kafka record. Spring's `JsonDeserializer` normally reads `__TypeId__` to know which Java class to deserialize into. Without it, you get:

```
org.apache.kafka.common.errors.SerializationException:
  Can't deserialize data [...] from topic [transactions]
Caused by: java.lang.IllegalStateException:
  No type information in headers and no default type provided
```

The fix is `spring.json.value.default.type`, which tells the deserializer: "When there is no type header, assume this class."

This is a **very common gotcha** when the producer is not a Spring application (Python, Go, Node.js, etc.).

---

### Critical: Why Jackson Handles BigDecimal and ZonedDateTime in the Typed DTO

You might wonder: "The JSON has `25.50` as a number and `2026-03-26T10:00:00.000000+00:00` as a string. How does Jackson know to convert these to `BigDecimal` and `ZonedDateTime`?"

**BigDecimal:** Jackson sees the Java field is `BigDecimal` and reads the JSON number `25.50` through `JsonParser.getDecimalValue()`, which returns `BigDecimal("25.50")` directly. No IEEE 754 double intermediate. This is **safer than** the `Map<String, Object>` approach where `25.50` becomes `Double` first and you need `BigDecimal.valueOf()`.

**ZonedDateTime:** The `jackson-datatype-jsr310` module (already in `build.gradle` line 37) registers a deserializer for `ZonedDateTime` that parses ISO 8601 strings. This works because the Python producer emits ISO format via `datetime.now(timezone.utc).isoformat()` (line 80).

**Important:** For `ZonedDateTime` deserialization to work, the Spring Boot application must have the `JavaTimeModule` registered with the `ObjectMapper`. Spring Boot auto-configures this when `jackson-datatype-jsr310` is on the classpath. The `JsonDeserializer` used by Spring Kafka creates its own internal `ObjectMapper` -- but Spring Boot's auto-configuration ensures `JavaTimeModule` is picked up. If it is NOT picked up (rare edge case), add to `application.yml`:

```yaml
spring:
  kafka:
    consumer:
      properties:
        spring.json.use.type.headers: false
  jackson:
    serialization:
      write-dates-as-timestamps: false
```

Or configure the `JsonDeserializer` with a custom `ObjectMapper` via a `ConsumerFactory` bean.

---

### Python Producer Timestamp Format Compatibility

The producer generates timestamps with `datetime.now(timezone.utc).isoformat()` (line 80).

Python `isoformat()` for UTC produces: `2026-03-26T10:00:00.123456+00:00`

Jackson's `ZonedDateTime` deserializer (from `jackson-datatype-jsr310`) handles this format. It supports:
- `2026-03-26T10:00:00.123456+00:00` -- microsecond precision with offset (Python default)
- `2026-03-26T10:00:00.000Z` -- millisecond precision with Z suffix
- `2026-03-26T10:00:00+00:00` -- second precision with offset

All three parse correctly to `ZonedDateTime`. No special configuration needed.

---

### Side-by-Side Comparison: All Three Approaches

| Aspect | Approach 1: StringDeserializer + ObjectMapper | Approach 2: JsonDeserializer + Map DTO | Approach 3: JsonDeserializer + Typed DTO |
|--------|-----------------------------------------------|----------------------------------------|------------------------------------------|
| **Deserializer config** | `StringDeserializer` | `JsonDeserializer` + trusted packages + default type | Same as Approach 2 |
| **Listener signature** | `consume(String message)` | `consume(KafkaTransactionEvent event)` | `consume(KafkaTransactionEvent event)` |
| **ObjectMapper needed** | Yes (injected, manual `readValue`) | No | No |
| **Manual map extraction** | Yes (50+ lines) | Yes (50+ lines) -- data is still `Map` | No -- data is typed |
| **BigDecimal safety** | Must use `BigDecimal.valueOf(double)` | Must use `BigDecimal.valueOf(double)` | Jackson handles directly (safer) |
| **ZonedDateTime parsing** | Must call `ZonedDateTime.parse()` | Must call `ZonedDateTime.parse()` | Jackson handles via jsr310 module |
| **Type safety** | None (`Map.get()` casts everywhere) | None (`Map.get()` casts everywhere) | Full compile-time type safety |
| **Lines of consumer code** | ~80 | ~75 | ~40 |
| **Modifies provided DTOs** | No | No | Yes (`KafkaTransactionEvent.data` type change) |
| **Error handling control** | Full (you catch parse exceptions) | Spring catches deser errors (configurable) | Same as Approach 2 |
| **Config complexity** | Minimal | Moderate (trusted packages, default type) | Same as Approach 2 |
| **Poison pill handling** | Catch in try/catch, log, skip | Wrap with `ErrorHandlingDeserializer` or message fails silently | Same as Approach 2 |

---

### ErrorHandlingDeserializer: Production Safety for JsonDeserializer

When using `JsonDeserializer`, if a malformed message arrives (e.g., the poison pill from `start-all.sh`), deserialization fails BEFORE your `@KafkaListener` method is called. The consumer will retry the same offset forever and block the partition.

**Solution:** Wrap with `ErrorHandlingDeserializer`:

```yaml
spring:
  kafka:
    consumer:
      key-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.key.delegate.class: org.apache.kafka.common.serialization.StringDeserializer
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: "com.vega.techtest.dto"
        spring.json.value.default.type: "com.vega.techtest.dto.KafkaTransactionEvent"
```

With `ErrorHandlingDeserializer`, a malformed message is delivered to the listener as `null` (or you can configure a custom error handler). The consumer does NOT get stuck.

**For the interview:** Mention this as a production hardening step, but do not implement it in the first pass. The `StringDeserializer` approach handles poison pills more naturally because deserialization is inside your try-catch.

---

### Recommendation for the Interview

**Use Approach 1 (StringDeserializer) as your primary implementation.** Here is why:

1. **It is the safest for a time-boxed interview.** No config gotchas with trusted packages, default type, or `ErrorHandlingDeserializer`. You control the entire deserialization pipeline.

2. **Poison pill handling is trivial.** A malformed JSON string hits your `try/catch` and gets logged. With `JsonDeserializer`, you need the `ErrorHandlingDeserializer` wrapper or the consumer blocks.

3. **The provided `KafkaTransactionEvent` has `Map<String, Object> data`.** Using `JsonDeserializer` with this DTO saves you exactly one line. Not worth the config overhead during the interview.

4. **It demonstrates you understand the deserialization pipeline.** Interviewers want to see that you know what Jackson does under the hood, not just that you can configure Spring to hide it.

**Then mention Approach 3 as a "how I would do this in production" discussion point:**

> "In production, I would create a strongly-typed payload DTO so Jackson handles all the type conversions -- BigDecimal, ZonedDateTime, nested lists -- automatically. That eliminates the unsafe Map casting and gives compile-time safety. Combined with JsonDeserializer and ErrorHandlingDeserializer, the consumer becomes very concise. But for this exercise, I am keeping the provided DTO structure and using StringDeserializer so I have full control over error handling."

This shows:
- You know the Spring Kafka ecosystem
- You make pragmatic trade-offs under time pressure
- You can articulate the production-grade approach without over-engineering the interview solution

---

### Quick Reference: application.yml for Each Approach

**Approach 1 -- StringDeserializer (interview default):**
```yaml
spring:
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

**Approach 2 -- JsonDeserializer with existing Map DTO:**
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: tech-test-consumer
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      enable-auto-commit: false
      properties:
        spring.json.trusted.packages: "com.vega.techtest.dto"
        spring.json.value.default.type: "com.vega.techtest.dto.KafkaTransactionEvent"
    listener:
      ack-mode: record
```

**Approach 3 -- JsonDeserializer with typed DTO + ErrorHandlingDeserializer (production):**
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: tech-test-consumer
      auto-offset-reset: earliest
      key-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      enable-auto-commit: false
      properties:
        spring.deserializer.key.delegate.class: org.apache.kafka.common.serialization.StringDeserializer
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: "com.vega.techtest.dto"
        spring.json.value.default.type: "com.vega.techtest.dto.KafkaTransactionEvent"
    listener:
      ack-mode: record
```

---

## The Mystery Second Kafka Topic: `tech-test-topic`

### Discovery

The `start-all.sh` script (line 57-58) creates **two** Kafka topics:

```bash
# Line 57 -- the mystery topic
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic tech-test-topic --partitions 3 --replication-factor 1

# Line 58 -- the known transactions topic
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic transactions --partitions 3 --replication-factor 1
```

The same dual creation appears in `start-all.ps1` (line 73) and `start-all.bat` (line 69).

### Evidence: `tech-test-topic` Is Completely Unused

| Where searched | References found |
|---|---|
| All Java source (`src/`) | 0 |
| `application.yml` / `application-test.yml` | 0 |
| `build.gradle` | 0 |
| `kafka_producer.py` | 0 (only publishes to `transactions`) |
| `till_simulator.py` | 0 |
| `docker-compose.yml` | 0 |
| All README / docs files | 0 |
| `start-all.sh` sample data loop | Only publishes to `transactions` |

**Nobody publishes to it. Nobody consumes from it. No documentation mentions it.**

### Analysis: What Is It For?

Three hypotheses, ranked by likelihood:

**Hypothesis 1 (Most Likely): Pre-provisioned Dead Letter Topic (DLT)**

The interviewer will likely ask: "What happens when your consumer encounters a poison pill or a message it can't deserialize?" The production-grade answer is to route failures to a DLT. Having `tech-test-topic` pre-created means the candidate can immediately wire it up as a DLT without spending time on `kafka-topics --create` during the interview.

Supporting evidence:
- The `start-all.sh` sample data includes a deliberate poison pill message: `BAD-TXN` with `"total":"not-a-number"`, `"timestamp":"INVALID-DATE"`, empty `customerId`, empty `items`
- The topic has 3 partitions (matching `transactions`), so it can use the same partition key (`transactionId`)
- Spring Kafka's `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` is a standard pattern the interviewer likely wants to see

If this is the DLT, your consumer should wire it up like this:

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

**Hypothesis 2: Output/Notification Topic for Downstream Systems**

After persisting a transaction from Kafka, the consumer publishes a lightweight "processed" event to `tech-test-topic` so downstream systems (loyalty, analytics, fraud) can react. This tests CQRS / event-driven architecture understanding.

Example flow:
```
transactions topic --> Consumer --> PostgreSQL
                                --> Publish to tech-test-topic: {transactionId, status: "PROCESSED", timestamp}
```

**Hypothesis 3 (Least Likely): Leftover Scaffolding**

The generic name `tech-test-topic` suggests it might be a leftover from early interview design before the `transactions` topic was named. However, the fact it appears in all three platform-specific scripts (sh, ps1, bat) suggests deliberate inclusion.

### Interview Strategy

**If the interviewer asks about error handling:**
- Mention `tech-test-topic` as a natural DLT candidate
- Say: "I noticed the startup script pre-creates a second topic -- I'd use that as a dead letter topic for messages that fail deserialization or processing after N retries"
- This demonstrates you actually explored the infrastructure, not just the Java code

**If the interviewer asks "what would you do next?" after the consumer is working:**
- Propose publishing processed events to `tech-test-topic` as an output channel
- Frame it as enabling downstream consumers (loyalty points, fraud detection, analytics) without coupling them to your consumer's database

**If neither comes up:**
- Do not volunteer it unprompted during implementation -- focus on getting the consumer working first
- Save it as a "bonus insight" for the architecture discussion portion

### Key Takeaway

The existence of `tech-test-topic` -- pre-created, unused, with matching partition count -- is strong evidence that the interview has a **follow-up task** beyond the basic consumer. Be ready to use it for DLT or event forwarding when the interviewer opens that door.

---

## Test-Driven Analysis: What `KafkaMessageDeserializationTest` Reveals

### What the Test Does -- Line by Line

The test file at `src/test/java/com/vega/techtest/KafkaMessageDeserializationTest.java` contains exactly **three tests**. Every single one uses the same deserialization approach.

#### Test 1: `testKafkaMessageDeserialization()`

```java
private final ObjectMapper objectMapper = new ObjectMapper();   // line 20

KafkaTransactionEvent event = objectMapper.readValue(kafkaMessage, KafkaTransactionEvent.class);  // line 62
```

- Creates a plain `ObjectMapper` (not Spring-injected, not configured with modules)
- Calls `objectMapper.readValue(String, KafkaTransactionEvent.class)` directly
- Asserts all five envelope fields as **Strings** via getters: `getEventId()`, `getEventType()`, `getEventTimestamp()`, `getSource()`, `getVersion()`
- Gets `data` as `Map<String, Object>` via `event.getData()` (line 73)
- Asserts data fields via `data.get("transactionId")`, `data.get("customerId")`, etc. -- all using `Map.get()`
- Checks `totalAmount` as a **raw Double** (line 80: `assertEquals(25.50, data.get("totalAmount"))`)
- Checks `items` via `instanceof java.util.List` (line 86) -- NOT as typed objects

#### Test 2: `testKafkaMessageWithDifferentDataTypes()`

- Same pattern: `objectMapper.readValue(kafkaMessage, KafkaTransactionEvent.class)` (line 122)
- Same `Map<String, Object> data = event.getData()` (line 131)
- Comment on line 133: `// Should be Double from JSON` -- the test author explicitly expects Double, not BigDecimal

#### Test 3: `testKafkaMessageSerialization()`

- Constructs `KafkaTransactionEvent` using the **all-args constructor**: `new KafkaTransactionEvent(String, String, String, String, String, Map.of(...))` (line 140-151)
- This constructor takes `Map<String, Object>` as the 6th argument -- confirming the DTO is designed for untyped maps
- Tests round-trip serialization: write to JSON, read back, verify

### What the Test Does NOT Do

| Missing Test Case | Implication |
|---|---|
| No `JsonDeserializer` usage | The test does not use Spring Kafka deserialization at all |
| No `ZonedDateTime` parsing | `eventTimestamp` and `data.timestamp` are asserted as plain Strings |
| No `BigDecimal` conversion | `totalAmount` is asserted as `Double` (25.50), not `BigDecimal` |
| No typed item deserialization | Items are only checked via `instanceof java.util.List` -- individual item fields are never validated |
| No poison pill / BAD-TXN test | No malformed JSON test, no missing-field test, no error case |
| No `KafkaTransactionPayload` class | No typed payload class exists or is referenced |
| No `JavaTimeModule` registered | Plain `new ObjectMapper()` without `registerModule(new JavaTimeModule())` |

### The DTO Confirms the Map Approach

`KafkaTransactionEvent.java` line 10-11:
```java
/**
 * The data field is a generic Map to allow candidates to parse it themselves
 */
```

This comment is the smoking gun. The phrase **"to allow candidates to parse it themselves"** is an explicit design decision. They WANT you to work with `Map<String, Object>` and demonstrate the manual extraction.

The DTO has:
- `@JsonProperty` annotations on all fields (works with plain ObjectMapper)
- `Map<String, Object> data` (not a typed payload)
- No-arg constructor (Jackson compatibility) + all-args constructor (test convenience)
- No Jackson module dependencies (no `@JsonDeserialize`, no `JavaTimeModule` annotations)

### Critical Finding: Two Different Message Formats in the System

**Format A -- from `kafka_producer.py` (the continuous producer):**
```json
{
  "eventId": "uuid",
  "eventType": "TRANSACTION_CREATED",
  "eventTimestamp": "...",
  "source": "till-system",
  "version": "1.0",
  "data": { "transactionId": "...", "customerId": "...", ... }
}
```
This is the envelope format that `KafkaTransactionEvent` deserializes.

**Format B -- from `start-all.sh` seed data (lines 68-78):**
```json
{
  "transactionId": "TXN-001",
  "timestamp": "2024-06-24T10:15:30Z",
  "customerId": "CUST-12345",
  "items": [{"name": "Milk", "price": 2.50, "quantity": 1}],
  "total": 4.90,
  "paymentMethod": "card",
  "storeId": "STORE-001"
}
```
This is a FLAT format -- no envelope, no `eventId`/`eventType`/`source`/`version` wrapper, no `data` nesting. Also note:
- Field is `total` (not `totalAmount`)
- Field is `price` (not `unitPrice`)
- Field is `name` (not `productName`)
- No `tillId`, no `currency`, no `productCode`, no `category`

**The seed data and the producer use different schemas.** If you deserialize a seed message into `KafkaTransactionEvent`, all envelope fields (`eventId`, `eventType`, `source`, `version`) will be `null`, and the `data` map will also be `null` because the JSON has no `data` key -- the fields are at the top level.

**The poison pill (`BAD-TXN`) is in Format B (flat), not Format A (envelope).** So it will NOT crash the Jackson deserializer on `KafkaTransactionEvent` in the way you might expect -- it will simply produce a `KafkaTransactionEvent` with all-null envelope fields and null `data`. Your null-check on `event.getData()` already handles this.

### What This Means for Each Approach

| Approach | Handles Format A (producer) | Handles Format B (seed) | Handles BAD-TXN |
|---|---|---|---|
| **1: StringDeserializer + ObjectMapper** | Yes -- `readValue` into `KafkaTransactionEvent` | Partially -- deserializes but all fields null | Yes -- caught in try/catch or null-check on data |
| **2: JsonDeserializer + Map DTO** | Yes | Partially -- same null fields | Depends on ErrorHandlingDeserializer config |
| **3: JsonDeserializer + Typed DTO** | Yes (if typed payload) | Fails -- no `data` field to parse into typed class | Fails pre-listener unless ErrorHandlingDeserializer |

### Definitive Recommendation: Approach 1 (StringDeserializer + ObjectMapper)

The test file **unambiguously validates Approach 1**. Here is why this is now a definitive, not just pragmatic, recommendation:

1. **The test uses `new ObjectMapper()` and `readValue(String, KafkaTransactionEvent.class)`.** This is exactly what our consumer does: receive a String from Kafka, parse it with ObjectMapper, work with `Map<String, Object> data`. The test is a literal template for the consumer's deserialization logic.

2. **The DTO comment says "to allow candidates to parse it themselves."** Modifying `KafkaTransactionEvent` to use a typed payload class goes against the test author's stated intention. In an interview, contradicting the provided design without being asked is a risk.

3. **The test expects `Double` from `data.get("totalAmount")`, not `BigDecimal`.** This confirms the Jackson-default behavior of `Map<String, Object>` deserialization. The manual `BigDecimal.valueOf(number.doubleValue())` conversion in our consumer is exactly the right pattern.

4. **Two message formats exist.** The seed data from `start-all.sh` is flat JSON without the envelope wrapper. StringDeserializer handles this gracefully (you get a `KafkaTransactionEvent` with null fields, your null-check skips it). JsonDeserializer with a typed payload would throw before your code even runs.

5. **No error case tests exist.** The test file does not test malformed JSON, missing fields, or the BAD-TXN message. This means error handling is left to the candidate -- and StringDeserializer gives you the most control here because all parsing happens inside your try-catch.

### What to Say in the Interview

**During implementation (when writing the consumer):**

> "I am using StringDeserializer and manual ObjectMapper.readValue(), which is exactly the pattern shown in the provided KafkaMessageDeserializationTest. The data field is Map<String, Object> by design, so I handle the type conversions explicitly -- BigDecimal.valueOf for amounts, ZonedDateTime.parse for timestamps."

**If asked "why not JsonDeserializer?":**

> "Three reasons. First, the provided test and DTO are designed around manual ObjectMapper parsing -- the DTO comment literally says 'to allow candidates to parse it themselves.' Second, the Python producer does not set the __TypeId__ header, so I would need spring.json.value.default.type configuration. Third, I noticed the seed data in start-all.sh uses a flat format without the event envelope, which would cause deserialization failure if the deserializer expects KafkaTransactionEvent structure. StringDeserializer lets me handle both formats gracefully."

**If asked "how would you improve this in production?":**

> "I would introduce a strongly-typed payload DTO so Jackson handles BigDecimal and ZonedDateTime conversions at parse time, eliminating the unsafe Map casting. Wrap the JsonDeserializer in an ErrorHandlingDeserializer for poison pill resilience. And standardize on a single message schema -- the current system has two different formats on the same topic, which is a schema evolution problem waiting to happen."

### Summary: Test Alignment Scorecard

| Signal | Points to Approach 1 | Points to Approach 3 |
|---|---|---|
| Test uses `new ObjectMapper()` | Yes | -- |
| Test uses `readValue(String, Class)` | Yes | -- |
| Test asserts `Map<String, Object> data` | Yes | -- |
| Test asserts `Double` for `totalAmount` | Yes | -- |
| DTO comment: "parse it themselves" | Yes | -- |
| DTO field: `Map<String, Object> data` | Yes | -- |
| No `JavaTimeModule` in test ObjectMapper | Yes | -- |
| Two different message formats on topic | Yes (handles both) | -- (breaks on seed data) |
| No error case tests | Yes (you handle it) | -- |

**Score: 9-0 in favor of Approach 1.** The test is a blueprint. Follow it.
