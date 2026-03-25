# Domain Objects

**Priority:** Time permitting (~10-15 min). Do this AFTER bug fixes and Kafka consumer are working.

---

## Existing DTO Inventory

All DTOs live in `com.vega.techtest.dto` (`src/main/java/com/vega/techtest/dto/`).

| DTO | Type | Purpose | Fields |
|-----|------|---------|--------|
| `TransactionRequest` | Class | REST API input | transactionId, customerId, storeId, tillId, paymentMethod, totalAmount (BigDecimal), currency, timestamp (ZonedDateTime), items (List) |
| `TransactionResponse` | Class | REST API output | Same as Request + createdAt, status; timestamp has `@JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss.SSSZ")` |
| `TransactionItemRequest` | Class | Item input | productName, productCode, unitPrice (BigDecimal), quantity (Integer), category |
| `TransactionItemResponse` | Class | Item output | Same as Request + totalPrice (BigDecimal) |
| `KafkaTransactionEvent` | Class | Kafka event envelope | eventId, eventType, eventTimestamp, source, version, data (`Map<String, Object>`) |

**Key observation:** Every controller endpoint currently returns `ResponseEntity<Map<String, Object>>` or `ResponseEntity<Map<String, String>>` using `Map.of(...)`. No compile-time contract, no self-documenting API.

---

## 6 New Response Records to Create

All records go in `com.vega.techtest.dto` alongside existing DTOs.

### 1. SubmitTransactionResponse -- POST /submit success

**CRITICAL:** The till simulator (`till_simulator.py` lines 611-626) validates the response contains **exactly** 4 fields: `{status, message, transactionId, timestamp}`. Any extra fields cause rejection.

```java
package com.vega.techtest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.ZonedDateTime;

@JsonPropertyOrder({"status", "message", "transactionId", "timestamp"})
public record SubmitTransactionResponse(
    String status,
    String message,
    String transactionId,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    ZonedDateTime timestamp
) {
    public static SubmitTransactionResponse success(String transactionId, ZonedDateTime timestamp) {
        return new SubmitTransactionResponse(
            "success", "Transaction processed successfully", transactionId, timestamp);
    }
}
```

**Timestamp format:** Use `SSSXXX` which produces `+00:00`. Do NOT use `SSSZ` which produces `+0000` -- Python's `datetime.fromisoformat()` cannot parse `+0000` without the colon.

### 2. ErrorResponse -- All error paths

```java
package com.vega.techtest.dto;

public record ErrorResponse(
    String status,
    String message,
    String error
) {
    public static ErrorResponse badRequest(String message, String errorDetail) {
        return new ErrorResponse("error", message, errorDetail);
    }

    public static ErrorResponse serverError(String message) {
        return new ErrorResponse("error", message, "Internal server error");
    }
}
```

Replaces 8+ instances of `Map.of("status", "error", ...)` across all controller catch blocks.

### 3. TransactionListResponse -- /store, /customer, /till, /date-range

```java
package com.vega.techtest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.ZonedDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionListResponse(
    String storeId,
    String customerId,
    String tillId,
    ZonedDateTime startDate,
    ZonedDateTime endDate,
    int count,
    List<TransactionResponse> transactions
) {
    public static TransactionListResponse forStore(String storeId, List<TransactionResponse> txns) {
        return new TransactionListResponse(storeId, null, null, null, null, txns.size(), txns);
    }

    public static TransactionListResponse forCustomer(String customerId, List<TransactionResponse> txns) {
        return new TransactionListResponse(null, customerId, null, null, null, txns.size(), txns);
    }

    public static TransactionListResponse forTill(String tillId, List<TransactionResponse> txns) {
        return new TransactionListResponse(null, null, tillId, null, null, txns.size(), txns);
    }

    public static TransactionListResponse forDateRange(ZonedDateTime start, ZonedDateTime end,
                                                        List<TransactionResponse> txns) {
        return new TransactionListResponse(null, null, null, start, end, txns.size(), txns);
    }
}
```

**Key:** `@JsonInclude(NON_NULL)` is mandatory so `forStore()` does not serialize `"customerId": null`, `"tillId": null`, etc. Without it, the JSON response includes null fields that do not exist in the current `Map.of()` output.

### 4. SampleTransactionResponse -- POST /sample

```java
package com.vega.techtest.dto;

public record SampleTransactionResponse(
    String status,
    String message,
    TransactionResponse transaction
) {
    public static SampleTransactionResponse success(TransactionResponse txn) {
        return new SampleTransactionResponse("success", "Sample transaction created", txn);
    }
}
```

### 5. StoreStatsResponse -- GET /stats/{storeId}

```java
package com.vega.techtest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoreStatsResponse(
    String storeId,
    String message,
    int totalTransactions,
    double totalAmount,
    double averageAmount,
    String calculationNote
) {
    public static StoreStatsResponse empty(String storeId) {
        return new StoreStatsResponse(
            storeId, "No transactions found for this store", 0, 0.0, 0.0, null);
    }

    public static StoreStatsResponse of(String storeId, int total, double totalAmt, double avgAmt) {
        return new StoreStatsResponse(storeId, null, total, totalAmt, avgAmt,
            "Average calculated as total amount divided by transaction count");
    }
}
```

**Key:** `@JsonInclude(NON_NULL)` is required. The empty case has `message` but no `calculationNote`; the data case has `calculationNote` but no `message`.

### 6. HealthResponse -- GET /health

```java
package com.vega.techtest.dto;

public record HealthResponse(
    String status,
    String service,
    String timestamp
) {
    public static HealthResponse up() {
        return new HealthResponse("UP", "transaction-service",
            String.valueOf(System.currentTimeMillis()));
    }
}
```

---

## Controller Before/After Changes

### POST /submit success (line 131)
```java
// BEFORE:
return ResponseEntity.ok(Map.of(
    "status", "success",
    "message", "Transaction processed successfully",
    "transactionId", response.getTransactionId(),
    "timestamp", response.getTransactionTimestamp()
));

// AFTER:
return ResponseEntity.ok(SubmitTransactionResponse.success(
    response.getTransactionId(), response.getTransactionTimestamp()));
```

### All error paths (lines 143, 153, 183, 213, etc.)
```java
// BEFORE:
return ResponseEntity.badRequest().body(Map.of(
    "status", "error",
    "message", "Invalid transaction data",
    "error", e.getMessage()
));

// AFTER:
return ResponseEntity.badRequest().body(ErrorResponse.badRequest(
    "Invalid transaction data", e.getMessage()));
```

### GET /store/{storeId} (line 203)
```java
// BEFORE:
return ResponseEntity.ok(Map.of(
    "storeId", storeId,
    "count", transactions.size(),
    "transactions", transactions
));

// AFTER:
return ResponseEntity.ok(TransactionListResponse.forStore(storeId, transactions));
```

### GET /stats/{storeId} empty (line 396)
```java
// BEFORE:
return ResponseEntity.ok(Map.of(
    "storeId", storeId,
    "message", "No transactions found for this store",
    "totalTransactions", 0,
    "totalAmount", 0.0,
    "averageAmount", 0.0
));

// AFTER:
return ResponseEntity.ok(StoreStatsResponse.empty(storeId));
```

### GET /stats/{storeId} data (line 419)
```java
// BEFORE:
return ResponseEntity.ok(Map.of(
    "storeId", storeId,
    "totalTransactions", totalTransactions,
    "totalAmount", totalAmount.doubleValue(),
    "averageAmount", averageAmount.doubleValue(),
    "calculationNote", "Average calculated as total amount divided by transaction count"
));

// AFTER:
return ResponseEntity.ok(StoreStatsResponse.of(
    storeId, totalTransactions, totalAmount.doubleValue(), averageAmount.doubleValue()));
```

### Return type widening
Change controller return types from `ResponseEntity<Map<String, Object>>` to `ResponseEntity<?>` so success and error paths can return different DTO types from the same method.

---

## Quick Reference Table

| Endpoint | Current Return | New DTO | Factory Method |
|---|---|---|---|
| POST /submit success | `Map<String, Object>` | `SubmitTransactionResponse` | `.success(id, ts)` |
| All errors (400/500) | `Map<String, Object>` | `ErrorResponse` | `.badRequest()` / `.serverError()` |
| GET /store/{id} | `Map<String, Object>` | `TransactionListResponse` | `.forStore(id, txns)` |
| GET /customer/{id} | `Map<String, Object>` | `TransactionListResponse` | `.forCustomer(id, txns)` |
| GET /till/{id} | `Map<String, Object>` | `TransactionListResponse` | `.forTill(id, txns)` |
| GET /date-range | `Map<String, Object>` | `TransactionListResponse` | `.forDateRange(s, e, txns)` |
| POST /sample | `Map<String, Object>` | `SampleTransactionResponse` | `.success(txn)` |
| GET /stats empty | `Map<String, Object>` | `StoreStatsResponse` | `.empty(storeId)` |
| GET /stats data | `Map<String, Object>` | `StoreStatsResponse` | `.of(id, total, amt, avg)` |
| GET /health | `Map<String, String>` | `HealthResponse` | `.up()` |

---

## Till Simulator Validation Constraints

The till simulator (`till-simulator/till_simulator.py`) is extremely strict about the POST /submit response format.

### Exact 4-Field Response Validation (lines 611-626)

```python
expected_fields = {"status", "message", "transactionId", "timestamp"}
actual_fields = set(result.keys())
if actual_fields != expected_fields:
    # REJECTS the response
```

The response MUST contain **exactly** these 4 fields, no more, no less:

```json
{
    "status": "success",
    "message": "Transaction processed successfully",
    "transactionId": "TXN-12345678",
    "timestamp": "2026-03-26T10:00:00.000+00:00"
}
```

### Field Validation Rules

| Field | Rule | Code Reference |
|-------|------|----------------|
| `status` | Must be exactly `"success"` | Line 560 |
| `message` | Must contain "success" (case-insensitive), >= 10 chars, not empty | Lines 565-575 |
| `transactionId` | Must match submitted ID exactly, must start with "TXN-" | Lines 578-585 |
| `timestamp` | Valid ISO format, within 5 minutes of now, not in future | Lines 588-609 |
| Extra fields | NOT ALLOWED -- exactly 4 fields | Lines 611-626 |
| Field types | All 4 must be strings | Lines 628-643 |

---

## Actuator vs Custom Health Endpoint

### Current State

**Custom endpoint** (`TransactionController.java` lines 372-380):
```java
@GetMapping("/health")
public ResponseEntity<Map<String, String>> health() {
    return ResponseEntity.ok(Map.of(
        "status", "UP",
        "service", "transaction-service",
        "timestamp", String.valueOf(System.currentTimeMillis())
    ));
}
```
This is a shallow liveness probe only.

**Actuator is already configured** (`application.yml` lines 29-51, `build.gradle` line 28):
- `/actuator/health` is live with `show-details: always`
- Reports: db (PostgreSQL), diskSpace, ping
- After adding spring-kafka, automatically adds Kafka broker health

### Recommendation: Keep Both

| Factor | Custom `/api/transactions/health` | Actuator `/actuator/health` |
|---|---|---|
| Depth | Shallow (liveness only) | Deep (readiness + liveness) |
| Who calls it | Till simulator (line 398) | Prometheus, Kubernetes, ops |
| Risk of removal | **Till simulator breaks** | N/A |
| After spring-kafka | No change | Adds Kafka health auto |

**The till simulator is the blocker.** It calls `/api/transactions/health` at line 398 and validates `status == "UP"`. Removing the custom endpoint breaks the simulator.

**Interview talking point:**
> "I noticed the project already has spring-boot-starter-actuator configured with show-details: always. So /actuator/health is already providing deep health checks for Postgres and disk space. Once I add spring-kafka, it will automatically pick up Kafka broker health too. The custom /api/transactions/health endpoint is a shallow liveness check -- I would keep it because the till simulator depends on it, but in production I would wire Kubernetes readiness probes to /actuator/health/readiness and liveness probes to /actuator/health/liveness."

---

## Money Storage Discussion

### Current State

| Layer | Type |
|---|---|
| Database | `decimal(10,2)` (PostgreSQL NUMERIC) |
| Java entities | `BigDecimal` with precision=10, scale=2 |
| Java DTOs | `BigDecimal` |
| Kafka producer (Python) | `float` literals, `round(total, 2)` |
| Kafka JSON | JSON numbers (IEEE 754 floats) |

### Decimal (Current) vs Lowest Denomination (Integer)

| Factor | Decimal (NUMERIC + BigDecimal) | Integer (BIGINT + Long, pence) |
|---|---|---|
| Readability | Human-readable everywhere | Need mental conversion (769 -> 7.69) |
| DB aggregation | `SUM(total_amount)` returns pounds | `SUM(total_amount) / 100.0` everywhere |
| Cross-language safety | JSON number 7.69 might parse as double | Integer 769 is exact in every language |
| Multi-currency | Simple (just change currency field) | Need ISO 4217 lookup (JPY=0dp, BHD=3dp, GBP=2dp) |
| Conversion bugs | None | Every boundary needs conversion. Forgotten conversion = 100x wrong |
| Industry precedent | Standard accounting practice | Stripe, Adyen, Square APIs |

### The Right Answer for This System

**Keep decimal(10,2) + BigDecimal. Do not change it.**

1. Supermarket transaction system, not a payment gateway
2. Single currency (GBP)
3. PostgreSQL NUMERIC is exact (base-10 packed decimal)
4. Java layer already uses BigDecimal throughout
5. Migration would take multi-sprint effort for zero value

**The Staff-level insight:** The "right" representation depends on the system boundary. Internal system with controlled serialization? NUMERIC + BigDecimal. External API consumed by arbitrary clients? Integers in lowest denomination.

**The one precision fix worth making:** Configure Jackson `USE_BIG_DECIMAL_FOR_FLOATS` in the Kafka consumer ObjectMapper to prevent Double parsing of money amounts.

---

## RoundingMode Deep Dive

### Quick Reference

| Property | DOWN | HALF_UP | HALF_EVEN |
|---|---|---|---|
| Plain English | Truncate | Round normally | Banker's rounding |
| Bias | Always loses value | Slight upward on .5 | Statistically unbiased |
| 7.695 -> ? | 7.69 | 7.70 | 7.70 (9 is odd, round up) |
| 7.685 -> ? | 7.68 | 7.69 | 7.68 (8 is even, stay) |
| IEEE 754 default | No | No | Yes |
| Python default | No | No | Yes |
| Use when | Regulation requires truncation | User-facing display | Financial calculations |

### Systematic Bias Over 1M Calculations

- **DOWN:** Rounds down 9/9 non-zero cases. 100% downward bias.
- **HALF_UP:** Rounds down 4, up 5 (out of 9). Slight upward bias.
- **HALF_EVEN:** Rounds down ~4.5, up ~4.5 (out of 9). Statistically unbiased.

### Transactional vs Display Rounding

- **Transactional rounding (money moves):** The rounded amount IS the amount. Overstating is dangerous. Some jurisdictions mandate DOWN for tax.
- **Display rounding (stats endpoint):** No money moves. 7.70 and 7.69 are equidistant from 7.695. What matters is no systematic drift over large datasets.

The stats endpoint computes an average for display. HALF_EVEN is correct.

---

## Timestamp Format Gotcha

**The problem:** Java's `ZonedDateTime.toString()` produces `2026-03-26T10:00:00+00:00[UTC]`. The `[UTC]` suffix causes Python's `datetime.fromisoformat()` to fail.

**The safe pattern:** Use `@JsonFormat` with `SSSXXX`:

```java
@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
```

- `SSSXXX` produces `+00:00` (with colon) -- Python can parse this
- `SSSZ` produces `+0000` (no colon) -- Python CANNOT parse this

**Current code note:** The controller returns `response.getTransactionTimestamp()` inside a `Map.of()` at line 135. Because it is in a Map (not a DTO), the `@JsonFormat` annotation on `TransactionResponse` does NOT apply. Jackson uses its default ZonedDateTime serializer. The till simulator handles this at line 594:
```python
response_timestamp = datetime.fromisoformat(result["timestamp"].replace('Z', '+00:00'))
```

If you switch to `SubmitTransactionResponse` (a record with `@JsonFormat`), the annotation WILL apply, giving you explicit control over the format.
