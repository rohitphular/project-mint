# Vega Tech Test -- Timed Practice Run (90 min)

Start a real timer. Talk out loud as you work -- narrate everything.

---

## Round 1: Bug Fix (Target: 10 min)

### Discovery

- [ ] Run `./scripts/start-all.sh` to bring up infra
- [ ] Run `./gradlew bootRun` to start the app
- [ ] Hit `GET /api/transactions/stats/STORE-001` -- observe wrong total and average
- [ ] Say out loud: "The total looks off, let me trace the calculation"

### Fix 1 -- TransactionController.java (stats endpoint)

- [ ] Find `.reduce(BigDecimal.ONE, BigDecimal::add)` on line ~409
- [ ] Fix to `.reduce(BigDecimal.ZERO, BigDecimal::add)`
- [ ] Say out loud: "Starting a sum at ONE instead of ZERO adds a phantom pound to every total"

### Fix 2 -- Calculator.java (average calculation)

- [ ] Find `++count` -- pre-increment means dividing by (count + 1)
- [ ] Fix to just `count`
- [ ] Say out loud: "Pre-increment is an off-by-one -- 3 transactions divided by 4"

### Fix 3 -- Calculator.java (rounding mode)

- [ ] Change `RoundingMode.DOWN` to `RoundingMode.HALF_EVEN`
- [ ] Say out loud: "DOWN truncates toward zero, which creates systematic bias against the customer. HALF_EVEN is banker's rounding -- no directional bias over many transactions"
- [ ] Mention: in production, store amounts in lowest denomination (pennies) to avoid floating-point entirely

### Fix 4 -- Method naming (quick win)

- [ ] Rename `calculateTotalAmount` to `calculateAverage` (it divides, not sums)
- [ ] Update the static import in TransactionController

### Verify

- [ ] Restart app, hit stats endpoint again
- [ ] Confirm: totalAmount = 23.07, averageAmount = 7.69 (for the 3 seed transactions via REST)

### Optional (3 min): Quick CalculatorTest

- [ ] Write a unit test: `Calculator.calculateAverage(new BigDecimal("30"), 3)` equals `new BigDecimal("10.00")`
- [ ] Test edge case: count = 0 should throw or return ZERO (discuss which is better)

**HARD STOP: If this round took > 15 min, you went too slow. Move on.**

---

## Round 2: Kafka Consumer (Target: 40 min)

### Step 1 -- Dependency (2 min)

- [ ] Add to build.gradle dependencies block:
  ```
  implementation 'org.springframework.kafka:spring-kafka'
  ```
- [ ] Refresh Gradle

### Step 2 -- Consumer Config in application.yml (3 min)

- [ ] Add Kafka consumer config under `spring:` block:
  ```yaml
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: tech-test-consumer-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
  ```
- [ ] Say out loud: "Using StringDeserializer so we control deserialization and can handle poison pills gracefully. earliest means we pick up existing messages."

### Step 3 -- Consumer Class (15 min)

- [ ] Create `TransactionKafkaConsumer.java` in the `service` package
- [ ] Annotate with `@Component`
- [ ] Inject `TransactionService` and `ObjectMapper`
- [ ] Add `@KafkaListener(topics = "transactions", groupId = "tech-test-consumer-group")`
- [ ] Deserialize String payload to `KafkaTransactionEvent` using `objectMapper.readValue()`
- [ ] Map `event.getData()` (Map<String,Object>) to `TransactionRequest`:
  - [ ] `BigDecimal.valueOf((Number) data.get("total")).setScale(2)` for totalAmount -- NOT `new BigDecimal(double)` which has floating-point noise
  - [ ] `ZonedDateTime.parse((String) data.get("timestamp"))` for timestamp
  - [ ] Iterate `List<Map<String,Object>>` items, build `TransactionItemRequest` for each
- [ ] Call `transactionService.processTransaction(request)`

### Step 4 -- Error Handling (5 min)

- [ ] Wrap entire listener body in try/catch
- [ ] Log the full payload on error for debugging
- [ ] Do NOT rethrow -- a rethrow causes infinite retry on poison pills
- [ ] Say out loud: "In production I would send this to a dead letter topic. For now, logging and skipping is the safe choice so one bad message does not block the partition."

### Step 5 -- Idempotency Discussion (say out loud, 2 min)

- [ ] Before writing the consumer, mention: "We should consider idempotency. If the consumer re-reads a message after a rebalance, we could double-count a transaction. I would check if transactionId already exists before inserting."
- [ ] Bonus: add a `findByTransactionId` check before calling processTransaction

### Verify

- [ ] Restart app
- [ ] Watch logs -- messages from the `transactions` topic should be consumed
- [ ] Hit `GET /api/transactions/store/STORE-001` -- Kafka-sourced transactions should appear alongside REST ones
- [ ] Confirm BAD-TXN is logged as an error but does NOT crash the consumer

---

## Round 3: Polish (Target: 15 min, only if time allows)

- [ ] Add a Micrometer counter for `kafka_messages_consumed_total` and `kafka_messages_failed_total`
- [ ] Response DTOs for the stats endpoint (instead of raw Map)
- [ ] Unit test for the consumer mapping logic (mock ObjectMapper, verify TransactionRequest fields)
- [ ] Integration test with `@EmbeddedKafka` if feeling ambitious

---

## Talking Points -- Practice These Out Loud

Say each of these at the right moment during your run:

| When | What to say |
|------|-------------|
| Finding BigDecimal.ONE | "This seeds the accumulator at 1 instead of 0 -- every store total is inflated by 1 pound" |
| Finding ++count | "Pre-increment is a classic off-by-one. Dividing by n+1 deflates every average" |
| Choosing RoundingMode | "HALF_EVEN is banker's rounding. DOWN truncates, creating systematic bias. Over millions of transactions that bias becomes material" |
| Before writing consumer | "Let me think about idempotency first. Kafka gives us at-least-once delivery, so we need to handle duplicate messages" |
| Choosing StringDeserializer | "I am deliberately using String deserialization so I can catch malformed JSON in my own code rather than having the framework blow up" |
| Handling poison pill | "In production this goes to a DLT. For now, log-and-skip keeps the consumer alive" |
| BigDecimal.valueOf vs new | "new BigDecimal(0.1) gives 0.1000000000000000055... because it takes the exact double. valueOf goes through String, giving exact 0.1" |
| If asked about scaling | "Consumer group parallelism is bounded by partition count. The topic has 3 partitions, so up to 3 consumer instances. Beyond that, repartition." |
| If asked about lowest denomination | "In production, store pennies as long integers. Eliminates all rounding issues at storage layer. Convert to pounds only at the presentation boundary." |

---

## Timing Guardrails

| Checkpoint | Time elapsed | If behind |
|------------|-------------|-----------|
| Bugs identified and fixed | 10 min | Skip rename, skip test, move on |
| Bugs verified working | 15 min | Hard cutoff -- start Kafka immediately |
| Kafka dependency + config done | 20 min | On track |
| Consumer class compiles | 35 min | On track |
| Consumer verified consuming | 45 min | On track |
| Poison pill handled | 50 min | Start polish or discuss architecture |
| End | 65 min | Use remaining 25 min for discussion/polish |

---

## Pre-Run Checklist

- [ ] Docker Desktop is running
- [ ] `./scripts/start-all.sh` completes without errors
- [ ] `curl http://localhost:8080/api/transactions/health` returns UP
- [ ] Kafka UI at http://localhost:8081 shows the `transactions` topic with messages
- [ ] IDE is open with the project loaded
- [ ] Timer app is ready
