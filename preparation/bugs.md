# Bugs

**Time budget:** 7-10 min target, 18 min absolute max.
**Priority:** This is the warm-up. Finish fast so you have 40+ min for the Kafka consumer.

---

## The Two Deliberate Bugs

### Bug 1: Wrong Reduce Identity in TransactionController

**File:** `src/main/java/com/vega/techtest/controller/TransactionController.java`
**Method:** `getTransactionStats()` (starts at line 385)
**Exact location:** Line 409

```java
// LINE 409 -- THE BUG: BigDecimal.ONE instead of BigDecimal.ZERO
BigDecimal totalAmount = transactions.stream()
        .map(TransactionResponse::getTotalAmount)
        .reduce(BigDecimal.ONE, BigDecimal::add);
```

**What it does wrong:** The `reduce` identity value is `BigDecimal.ONE` (1.00) instead of `BigDecimal.ZERO`. The sum always starts at 1.00 instead of 0.00, inflating every store's total by exactly 1.00.

**The fix (line 409):**
```java
BigDecimal totalAmount = transactions.stream()
        .map(TransactionResponse::getTotalAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

---

### Bug 2: Pre-Increment in Calculator

**File:** `src/main/java/com/vega/techtest/utils/Calculator.java`
**Method:** `calculateTotalAmount()` (line 8)
**Exact location:** Line 9

```java
// LINE 9 -- THREE ISSUES IN ONE LINE:
// 1. ++count (pre-increment) divides by count+1 instead of count
// 2. Method named "calculateTotalAmount" but actually calculates AVERAGE
// 3. RoundingMode.DOWN systematically loses value
public static BigDecimal calculateTotalAmount(BigDecimal totalAmount, int count) {
    return totalAmount.divide(BigDecimal.valueOf(++count), 2, RoundingMode.DOWN);
}
```

**Bug 2a -- Pre-increment:** `++count` increments count BEFORE using it. If count=3, it divides by 4 instead of 3.

**Bug 2b -- Misleading name:** Called `calculateTotalAmount` but performs division (total / count = average).

**Bug 2c -- RoundingMode.DOWN:** Truncates toward zero. Systematically loses fractional pennies over millions of transactions. Financial standard is `HALF_UP` or `HALF_EVEN`.

**The fix -- complete rewrite of Calculator.java:**
```java
package com.vega.techtest.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Calculator {

    public static BigDecimal calculateAverage(BigDecimal totalAmount, int count) {
        if (count == 0) {
            return BigDecimal.ZERO;
        }
        return totalAmount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN);
    }
}
```

**Also update TransactionController.java:**
```java
// Line 3: Update import
import static com.vega.techtest.utils.Calculator.calculateAverage;

// Line 411: Update call
BigDecimal averageAmount = calculateAverage(totalAmount, totalTransactions);
```

---

## Combined Arithmetic Proof

Given 3 seed transactions at STORE-001, each with totalAmount = 7.69:

```
CORRECT:
  total   = 0.00 + 7.69 + 7.69 + 7.69 = 23.07
  average = 23.07 / 3 = 7.69

BUGGY:
  total   = 1.00 + 7.69 + 7.69 + 7.69 = 24.07   (Bug 1: identity = ONE)
  average = 24.07 / (3+1) = 24.07 / 4 = 6.0175   (Bug 2: ++count makes 3 -> 4)
          = 6.01                                    (RoundingMode.DOWN truncates)

EXPECTED stats response:
  totalTransactions: 3
  totalAmount: 23.07
  averageAmount: 7.69

ACTUAL buggy stats response:
  totalTransactions: 3
  totalAmount: 24.07
  averageAmount: 6.01
```

---

## RoundingMode: DOWN -> HALF_EVEN

This is a one-word change you make while fixing `++count`. It demonstrates financial domain awareness.

| Property | DOWN (current) | HALF_EVEN (fix) |
|---|---|---|
| Plain English | Truncate (chop digits) | Banker's rounding |
| Bias | Always loses value (100% downward) | Statistically unbiased |
| 7.695 -> ? | 7.69 | 7.70 (9 is odd, round up to even) |
| 7.685 -> ? | 7.68 | 7.68 (8 is already even, stay) |
| IEEE 754 default | No | Yes |
| Python default | No | Yes |
| Use when | Regulation requires truncation (e.g., HMRC VAT) | Financial calculations (default) |

**30-second talking point while making the change:**
> "I am also changing RoundingMode.DOWN to HALF_EVEN. DOWN truncates, which means every rounding operation systematically loses value. HALF_EVEN is banker's rounding -- it rounds to nearest, and on exact .5 boundaries it alternates based on whether the preceding digit is even. This eliminates statistical bias over large datasets. It is the IEEE 754 default and the standard for financial systems."

---

## Scripted Debugging Narration

This is what to SAY out loud during the pair programming session. Silence is death in pair programming.

### Step 1: Read the API Response (30 sec)

> "OK, so we have a clear discrepancy. The store endpoint shows 3 transactions, each with a totalAmount of 7.69. That means the correct total should be 23.07 and the correct average should be 7.69. But the stats endpoint reports 24.07 and 6.01. The total is off by exactly 1.00, which is interesting -- that does not look like a floating point precision issue or a rounding error. That looks like a constant offset. Let me trace the stats calculation."

### Step 2: Go to the Stats Endpoint Code (1 min)

> "The stats endpoint is at getTransactionStats, line 385. It fetches the transactions from the service, then computes stats inline. Let me look at the total calculation... here, line 407 to 409. It is a stream reduce over the transaction amounts."

### Step 3: Spot Bug 1 (30 sec)

> "And there it is. The reduce identity is BigDecimal.ONE. That should be BigDecimal.ZERO. The identity element for addition is zero -- using one means every total gets an extra pound added. That explains the 24.07 instead of 23.07."

### Step 4: Follow the Average Calculation (1 min)

> "Now let me check the average. Line 411 calls calculateTotalAmount, which is statically imported from Calculator. The naming is odd -- you would not expect a method called 'calculateTotalAmount' to compute an average. Let me open Calculator."

### Step 5: Spot Bug 2 (30 sec)

> "This is a small utility, only one method. It divides totalAmount by count... but it uses the pre-increment operator: plus-plus-count. That means the count is incremented before it is used. So when we pass in 3, it actually divides by 4. Combined with the inflated total from bug one, that gives us 24.07 divided by 4 equals 6.01, which matches the broken output exactly."

### Step 6: Make the Fixes (1 min)

> "Two fixes needed. First, change BigDecimal.ONE to BigDecimal.ZERO in the controller. Second, remove the pre-increment in Calculator -- just use count, not plus-plus-count. I would also rename this method to calculateAverage since that is what it actually does, and change RoundingMode.DOWN to HALF_EVEN for financial correctness."

### Step 7: Verify (1-2 min)

> "Let me verify. The stats endpoint now returns total 23.07 and average 7.69. That matches the individual transactions. Bug is fixed."

---

## Business Impact Talking Points

Use these to connect code to business outcomes:

- **BigDecimal.ONE identity:** Every store's revenue total is inflated by 1 pound. Multiply by thousands of stores and this creates phantom revenue. Reconciliation with actual bank settlements would fail.

- **++count (divides by n+1):** Every average is artificially deflated. If this feeds into dashboards, management sees lower average basket sizes than reality, which could drive incorrect business decisions (e.g., unnecessary promotions to boost basket size that is already healthy).

- **RoundingMode.DOWN:** Systematically loses fractional pennies. Over millions of transactions, this is not a rounding error -- it is a wealth transfer. For financial compliance, you need a statistically unbiased rounding mode.

---

## JUnit Test to Write AFTER Fixing

Write this AFTER fixing, not before. Target 3-5 minutes. A `CalculatorTest` already exists at `src/test/java/com/vega/techtest/utils/CalculatorTest.java` -- add to it or replace.

```java
package com.vega.techtest.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorTest {

    @Test
    @DisplayName("Average of three identical amounts should equal each amount")
    void calculateAverage_threeIdenticalAmounts_returnsExactAmount() {
        BigDecimal total = new BigDecimal("23.07"); // 3 x 7.69
        int count = 3;

        BigDecimal result = Calculator.calculateAverage(total, count);

        assertEquals(new BigDecimal("7.69"), result);
    }

    @Test
    @DisplayName("Average should use HALF_EVEN rounding (banker's rounding)")
    void calculateAverage_nonTerminatingDecimal_roundsCorrectly() {
        BigDecimal total = new BigDecimal("10.00");
        int count = 3;

        BigDecimal result = Calculator.calculateAverage(total, count);

        // 10.00 / 3 = 3.3333... -> 3.33
        assertEquals(new BigDecimal("3.33"), result);
    }

    @Test
    @DisplayName("Single transaction average equals its total")
    void calculateAverage_singleTransaction_returnsTotalAmount() {
        BigDecimal total = new BigDecimal("7.69");
        int count = 1;

        BigDecimal result = Calculator.calculateAverage(total, count);

        assertEquals(new BigDecimal("7.69"), result);
    }

    @Test
    @DisplayName("Zero count returns zero (division by zero guard)")
    void calculateAverage_zeroCount_returnsZero() {
        BigDecimal total = new BigDecimal("23.07");
        int count = 0;

        BigDecimal result = Calculator.calculateAverage(total, count);

        assertEquals(BigDecimal.ZERO, result);
    }
}
```

---

## Time Budget Breakdown

| Phase | Target | Max |
|-------|--------|-----|
| Read API output, note discrepancy | 30 sec | 1 min |
| Trace to stats endpoint code | 1 min | 2 min |
| Identify both bugs | 1 min | 3 min |
| Apply fixes | 1 min | 2 min |
| Verify fix via API | 1-2 min | 3 min |
| Write Calculator unit test | 3-5 min | 7 min |
| **Total** | **7-10 min** | **18 min** |

**Risk:** Spending more than 15 minutes here. The bugs are the warm-up -- the Kafka consumer is the main event. If you burn 20+ minutes on two straightforward bugs, the interviewer will question your prioritization.

---

## Off-by-One Pattern Recognition

Both bugs are variants of the same anti-pattern: **off-by-one in disguise**.

- `BigDecimal.ONE` instead of `BigDecimal.ZERO`: off by one in the seed value
- `++count` instead of `count`: off by one in the divisor

When a financial calculation is wrong by a small, constant amount (not a percentage, not a floating-point artifact, but exactly 1.00), look for:
1. Wrong identity/seed values in folds/reduces
2. Off-by-one in loop bounds or pre/post increment operators
3. Fencepost errors (inclusive vs exclusive ranges)

Say this out loud. It demonstrates pattern recognition.
