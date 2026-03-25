# Bug Fix Phase -- Definitive Reference

**Time budget:** 7-10 min target, 18 min absolute max.
**Priority:** This is the warm-up. Finish fast so you have 40+ min for the Kafka consumer.
**Remember:** The interviewer is watching HOW you debug, not just WHETHER you find it.

---

## Section 1: Overview

### What the Interviewer Will Say

The interviewer will frame it roughly like this:

> "We have a supermarket transaction system. There is a bug in the stats endpoint. The store endpoint shows the individual transactions correctly, but the stats endpoint is returning wrong numbers. Can you find and fix it?"

They may or may not tell you there are two bugs. Either way, start by hitting the two endpoints that expose the problem.

### The Two Endpoints to Hit First

```
GET /api/transactions/store/STORE-001    -- lists individual transactions (correct data)
GET /api/transactions/stats/STORE-001    -- computes aggregate stats (buggy output)
```

### Expected vs Actual Output

The seed data has 3 transactions for STORE-001, each with `totalAmount: 7.69`.

**Correct output (after fix):**
```json
{
  "storeId": "STORE-001",
  "totalTransactions": 3,
  "totalAmount": 23.07,
  "averageAmount": 7.69
}
```

**Buggy output (before fix):**
```json
{
  "storeId": "STORE-001",
  "totalTransactions": 3,
  "totalAmount": 24.07,
  "averageAmount": 6.01
}
```

### Quick Arithmetic Proof

```
Correct:  3 x 7.69 = 23.07      average = 23.07 / 3 = 7.69
Buggy:    24.07 (off by +1.00)   average = 24.07 / 4 = 6.0175 -> 6.01 (RoundingMode.DOWN)
```

The total is wrong by exactly 1.00 -- not a floating-point artifact, not a percentage error. A constant offset. That is your first clue.

---

## Section 2: Bug 1 -- BigDecimal.ONE Reduce Identity

**File:** `src/main/java/com/vega/techtest/controller/TransactionController.java`
**Method:** `getTransactionStats()` (starts at line 382 in the original)
**Exact bug location:** Line 409 in the original commit

### Buggy Code (BEFORE)

```java
// TransactionController.java -- original line 409
BigDecimal totalAmount = transactions.stream()
        .map(TransactionResponse::getTotalAmount)
        .reduce(BigDecimal.ONE, BigDecimal::add);
```

### Fixed Code (AFTER)

```java
// TransactionController.java -- line 403-405 in current code
BigDecimal totalAmount = transactions.stream()
        .map(TransactionResponse::getTotalAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

### Why It Is Wrong

`Stream.reduce(identity, accumulator)` -- the identity is the starting value of the accumulation. For addition, the identity element must be zero (because `0 + x = x` for all `x`). Using `BigDecimal.ONE` means every store's total starts at 1.00 before any transactions are summed. The sum is always inflated by exactly one pound.

### Business Impact

Every single store's revenue total is overstated by exactly 1.00. At first glance that sounds small, but:

- **Reconciliation failure:** When you reconcile store revenue against actual bank settlements, every store is off by 1.00. Across thousands of stores, that is thousands of phantom pounds.
- **Subtle enough to survive:** Unlike a 2x or 10x error, +1.00 on a total of hundreds or thousands of pounds is a tiny percentage. It could go unnoticed for weeks or months.
- **Audit implications:** In a regulated supermarket business, revenue mis-reporting (even upward) is a compliance issue.

### What to Say While Fixing It

> "OK, so the total is 24.07 but should be 23.07 -- off by exactly one pound. That is a constant offset, not a scaling error. Let me look at how the total is computed... here, the reduce identity is BigDecimal.ONE. That should be BigDecimal.ZERO. The identity element for addition is zero -- using ONE means the sum always starts at one instead of zero, inflating every store's total by exactly one pound."

---

## Section 3: Bug 2 -- Pre-increment in Calculator

**File:** `src/main/java/com/vega/techtest/utils/Calculator.java`
**Method:** `calculateTotalAmount()` (line 8 in the original)
**Exact bug location:** Line 9 in the original commit

### Buggy Code (BEFORE) -- Complete Original File

```java
package com.vega.techtest.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Calculator {

    public static BigDecimal calculateTotalAmount(BigDecimal totalAmount, int count) {
        return totalAmount.divide(BigDecimal.valueOf(++count), 2, RoundingMode.DOWN);
    }
}
```

Three issues packed into this one method:

| Issue | What Is Wrong | Effect |
|-------|--------------|--------|
| `++count` (pre-increment) | Increments count before using it | Divides by `count+1` instead of `count`. 3 transactions -> divides by 4 |
| `RoundingMode.DOWN` | Truncates toward zero | Systematically loses fractional pennies. 100% downward bias |
| Method name `calculateTotalAmount` | Says "total" but computes an average | Misleading; violates principle of least surprise |

### Fixed Code (AFTER) -- Complete Current File

```java
package com.vega.techtest.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Calculator {

    /**
     * Calculates the average transaction amount for a store.
     *
     * @param totalAmount the sum of all transaction amounts
     * @param count       the number of transactions
     * @return the average amount, rounded to 2 decimal places using banker's rounding
     * @throws IllegalArgumentException if count is negative
     */
    public static BigDecimal calculateAverageAmount(BigDecimal totalAmount, int count) {
        if (count == 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        if (count < 0) throw new IllegalArgumentException("Count cannot be negative");
        return totalAmount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN);
    }
}
```

### Every Change and Why

1. **`++count` changed to `count`** -- Remove the pre-increment. We want to divide by the actual count, not count+1.

2. **`RoundingMode.DOWN` changed to `RoundingMode.HALF_EVEN`** -- DOWN truncates (always rounds toward zero), which means every rounding operation systematically loses value. HALF_EVEN is banker's rounding -- statistically unbiased. See Section 5 for the deep dive.

3. **`calculateTotalAmount` renamed to `calculateAverageAmount`** -- The method divides total by count, which is an average, not a total. The name should reflect what it does.

4. **Zero-count guard added** -- `if (count == 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)` -- Prevents `ArithmeticException` (division by zero). Returns 0.00 with consistent scale.

5. **Negative-count guard added** -- `if (count < 0) throw new IllegalArgumentException(...)` -- A negative transaction count is a data integrity problem. Fail fast and loud rather than silently computing a negative average.

### What to Say While Fixing It

> "Now let me look at the average calculation. It calls calculateTotalAmount from Calculator... but the name is misleading -- this method divides total by count, which is an average, not a total. And here is the second bug: it uses plus-plus-count, the pre-increment operator. That means count is incremented before it is used as the divisor. So when we pass 3, it actually divides by 4."
>
> "I am also changing RoundingMode.DOWN to HALF_EVEN. DOWN truncates, which means every rounding operation systematically loses value. HALF_EVEN is banker's rounding -- it rounds to the nearest value, and on exact .5 boundaries it rounds to the even digit. This eliminates statistical bias. It is the IEEE 754 default and the standard for financial systems."
>
> "And I will add a zero guard to prevent division by zero, and a negative guard because a negative transaction count would indicate a data integrity issue."

---

## Section 4: Combined Bug Effect -- Arithmetic Walkthrough

Given 3 seed transactions at STORE-001, each with `totalAmount = 7.69`:

### Buggy Path (Both Bugs Active)

```
Step 1 -- Sum with wrong identity (Bug 1):
  reduce(BigDecimal.ONE, BigDecimal::add)
  = 1.00 + 7.69 + 7.69 + 7.69
  = 24.07                              <-- inflated by 1.00

Step 2 -- Average with pre-increment (Bug 2):
  calculateTotalAmount(24.07, 3)
  ++count -> count becomes 4
  24.07 / 4 = 6.0175

Step 3 -- RoundingMode.DOWN:
  6.0175 -> 6.01                       <-- truncated, not rounded

BUGGY RESULT: totalAmount=24.07, averageAmount=6.01
```

### Fixed Path (Both Bugs Fixed)

```
Step 1 -- Sum with correct identity:
  reduce(BigDecimal.ZERO, BigDecimal::add)
  = 0.00 + 7.69 + 7.69 + 7.69
  = 23.07                              <-- correct

Step 2 -- Average without pre-increment:
  calculateAverageAmount(23.07, 3)
  count stays 3
  23.07 / 3 = 7.69                     <-- exact, no rounding needed

CORRECT RESULT: totalAmount=23.07, averageAmount=7.69
```

### Why the Bugs Partially Mask Each Other

The two bugs create a compound error that could mislead a debugger:

- Bug 1 inflates the total by +1.00 (upward error in numerator)
- Bug 2 divides by n+1 instead of n (downward error via larger denominator)
- The net effect on the average is not intuitive: the average drops from 7.69 to 6.01

If you only fix one bug, the numbers are still wrong but in a different way. Both must be fixed together.

---

## Section 5: RoundingMode Deep Dive

### What Each Mode Does

| Mode | Plain English | 7.695 | 7.685 | 2.445 | 2.455 | Bias |
|------|--------------|-------|-------|-------|-------|------|
| **DOWN** | Truncate (chop extra digits) | 7.69 | 7.68 | 2.44 | 2.45 | 100% downward -- always loses value |
| **HALF_UP** | Round to nearest, ties go up | 7.70 | 7.69 | 2.45 | 2.46 | Slight upward bias on .5 boundaries |
| **HALF_EVEN** | Round to nearest, ties go to even digit | 7.70 | 7.68 | 2.44 | 2.46 | No systematic bias |

**Reading the HALF_EVEN column:**
- 7.695 -> 7.70 because the digit before the 5 is 9 (odd), so round UP to make it even (0)
- 7.685 -> 7.68 because the digit before the 5 is 8 (already even), so round DOWN (stay)
- 2.445 -> 2.44 because the digit before the 5 is 4 (already even), so round DOWN (stay)
- 2.455 -> 2.46 because the digit before the 5 is 5 (odd), so round UP to make it even (6)

### Values Where the Three Modes Differ

The modes only diverge on .5 boundaries and below. For most values (like 7.694 or 7.696), all three modes agree. The table above specifically shows tie-breaking cases where behaviour differs.

### Why HALF_EVEN Is the Standard for Financial Systems

1. **No systematic bias.** Over millions of transactions, DOWN always loses money (every rounding goes down). HALF_UP always gains slightly (ties go up). HALF_EVEN alternates -- ties go up half the time and down half the time, depending on the preceding digit's parity.

2. **IEEE 754 default.** HALF_EVEN is the default rounding mode in the IEEE 754 floating-point standard, which governs how processors do arithmetic.

3. **Industry standard.** Python's `decimal` module defaults to HALF_EVEN. Banks, clearinghouses, and financial regulators assume this mode unless a specific regulation says otherwise.

4. **Reconciliation.** When your system's numbers need to match another party's numbers (bank, regulator, auditor), using the same rounding mode is essential. HALF_EVEN is the safest assumption.

### The Overstating Concern

One nuance worth knowing: HALF_EVEN can round individual values *above* the original fractional amount (e.g., 7.695 -> 7.70, which is higher than the "true" value). In contexts where money is actually being moved (e.g., calculating a payment amount), some organisations prefer DOWN or specific regulatory modes to avoid overstating liabilities.

**For this system, HALF_EVEN is clearly correct.** The stats endpoint computes display averages for dashboards -- read-only statistics where no money moves. What matters is statistical accuracy over large aggregates, not conservative treatment of individual values.

### Transactional vs Display Rounding

| Context | What Matters | Good Choice |
|---------|-------------|-------------|
| **Transactional** (payments, settlements, tax) | Must not overstate; regulation may mandate specific mode | Depends on jurisdiction. DOWN for VAT in some regimes, HALF_UP for others. Check the regulation. |
| **Display** (dashboards, reports, averages) | Statistical accuracy over aggregates | HALF_EVEN -- minimises cumulative drift in either direction |

### The Ideal Architecture (Mention If Time Permits)

> "In an ideal world, you compute internally at high precision -- say scale=10 -- and only round to scale=2 at the display boundary. That way intermediate calculations do not accumulate rounding error. But for a simple average like this, dividing once at scale=2 with HALF_EVEN is perfectly fine."

---

## Section 6: The Hardened Calculator -- Final Code

This is the code currently in the codebase after your fix. Reference it during the interview.

```java
package com.vega.techtest.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Calculator {

    /**
     * Calculates the average transaction amount for a store.
     *
     * @param totalAmount the sum of all transaction amounts
     * @param count       the number of transactions
     * @return the average amount, rounded to 2 decimal places using banker's rounding
     * @throws IllegalArgumentException if count is negative
     */
    public static BigDecimal calculateAverageAmount(BigDecimal totalAmount, int count) {
        if (count == 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        if (count < 0) throw new IllegalArgumentException("Count cannot be negative");
        return totalAmount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN);
    }
}
```

The corresponding change in **TransactionController.java**:

```java
// Line 3: import changed
import static com.vega.techtest.utils.Calculator.calculateAverageAmount;

// Line 407: call site changed
BigDecimal averageAmount = calculateAverageAmount(totalAmount, totalTransactions);
```

---

## Section 7: Test Cases

### Your Current CalculatorTest (Already in Codebase)

Located at `src/test/java/com/vega/techtest/utils/CalculatorTest.java`:

```java
package com.vega.techtest.utils;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;


class CalculatorTest {

    @Test
    void calculateAverageAmount() {
        // Test case 1: Normal case
        assertEquals(new BigDecimal("10.00"), Calculator.calculateAverageAmount(new BigDecimal("100.00"), 10));

        // Test case 2: Zero count
        assertEquals(new BigDecimal("0.00"), Calculator.calculateAverageAmount(new BigDecimal("100.00"), 0));

        // Test case 3: Total amount is zero
        assertEquals(new BigDecimal("0.00"), Calculator.calculateAverageAmount(new BigDecimal("0.00"), 10));

        // Test case 4: Total amount less than count
        assertEquals(new BigDecimal("0.50"), Calculator.calculateAverageAmount(new BigDecimal("5.00"), 10));
    }

    @Test
    void calculateAverageAmountWithNegativeCount() {
        // Test case 5: Negative count should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            Calculator.calculateAverageAmount(new BigDecimal("100.00"), -5);
        });
    }
}
```

### Additional Recommended Tests

If the interviewer asks "would you write more tests?" or if you have time, these are the high-value additions:

```java
package com.vega.techtest.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorTest {

    // --- Existing tests (already in codebase) ---

    @Test
    void calculateAverageAmount() {
        assertEquals(new BigDecimal("10.00"), Calculator.calculateAverageAmount(new BigDecimal("100.00"), 10));
        assertEquals(new BigDecimal("0.00"), Calculator.calculateAverageAmount(new BigDecimal("100.00"), 0));
        assertEquals(new BigDecimal("0.00"), Calculator.calculateAverageAmount(new BigDecimal("0.00"), 10));
        assertEquals(new BigDecimal("0.50"), Calculator.calculateAverageAmount(new BigDecimal("5.00"), 10));
    }

    @Test
    void calculateAverageAmountWithNegativeCount() {
        assertThrows(IllegalArgumentException.class, () -> {
            Calculator.calculateAverageAmount(new BigDecimal("100.00"), -5);
        });
    }

    // --- Additional recommended tests ---

    @Test
    @DisplayName("Single transaction proves ++count bug is dead: 10.00/1 must equal 10.00, not 10.00/2=5.00")
    void singleTransaction_provesPreIncrementBugIsDead() {
        // If ++count were still present, this would return 5.00 (10.00 / 2)
        BigDecimal result = Calculator.calculateAverageAmount(new BigDecimal("10.00"), 1);
        assertEquals(new BigDecimal("10.00"), result);
    }

    @Test
    @DisplayName("Proves HALF_EVEN differs from DOWN: 5.00/3 = 1.67, not 1.66")
    void nonTerminating_provesHalfEvenNotDown() {
        // 5.00 / 3 = 1.66666...
        // DOWN would give 1.66 (truncate)
        // HALF_EVEN gives 1.67 (6 rounds up because 6 > 5)
        BigDecimal result = Calculator.calculateAverageAmount(new BigDecimal("5.00"), 3);
        assertEquals(new BigDecimal("1.67"), result);
    }

    @Test
    @DisplayName("HALF_EVEN tie-breaking: 2.50/2 = 1.25, but 1.50/2 = 0.75 -- no .5 boundary here, just verifying")
    void halfEvenTieBreaking() {
        // 7.695 scenario: create total that yields .XX5 after division
        // 15.39 / 2 = 7.695 -> HALF_EVEN rounds to 7.70 (9 is odd, round up)
        BigDecimal result = Calculator.calculateAverageAmount(new BigDecimal("15.39"), 2);
        assertEquals(new BigDecimal("7.70"), result);

        // 15.37 / 2 = 7.685 -> HALF_EVEN rounds to 7.68 (8 is even, stay)
        BigDecimal result2 = Calculator.calculateAverageAmount(new BigDecimal("15.37"), 2);
        assertEquals(new BigDecimal("7.68"), result2);
    }

    @Test
    @DisplayName("The exact interview scenario: 23.07/3 = 7.69")
    void interviewScenario_threeTransactionsAtSevenSixtyNine() {
        BigDecimal result = Calculator.calculateAverageAmount(new BigDecimal("23.07"), 3);
        assertEquals(new BigDecimal("7.69"), result);
    }

    @Test
    @DisplayName("Negative total (refund scenario): -50.00/5 = -10.00")
    void negativeTotal_refundScenario() {
        BigDecimal result = Calculator.calculateAverageAmount(new BigDecimal("-50.00"), 5);
        assertEquals(new BigDecimal("-10.00"), result);
    }

    @Test
    @DisplayName("Large values do not overflow: 999999.99/1 = 999999.99")
    void largeValues_noOverflow() {
        BigDecimal result = Calculator.calculateAverageAmount(new BigDecimal("999999.99"), 1);
        assertEquals(new BigDecimal("999999.99"), result);
    }
}
```

**Why these specific tests matter:**

| Test | What It Proves | Why It Matters |
|------|---------------|----------------|
| Single transaction (10.00, 1) | `++count` bug is dead | With the old code, 10.00/2 = 5.00. This is the most direct regression test. |
| Non-terminating (5.00, 3) | HALF_EVEN, not DOWN | DOWN gives 1.66, HALF_EVEN gives 1.67. Catches rounding mode regression. |
| HALF_EVEN tie-breaking | Banker's rounding on .5 boundaries | 7.695->7.70 and 7.685->7.68 prove correct tie-breaking behaviour. |
| Interview scenario (23.07, 3) | The exact numbers from the demo | Documents the specific scenario; if this test breaks, you know the interview bug has regressed. |
| Negative total (-50.00, 5) | Refunds work correctly | Supermarkets process refunds; negative amounts must not blow up. |
| Large values | No BigDecimal overflow | Sanity check for edge-of-range values. |

---

## Section 8: Controller Response -- BigDecimal to Double Discussion

### The Issue

In `TransactionController.java`, the stats response does this (lines 418-419):

```java
"totalAmount", totalAmount.doubleValue(),
"averageAmount", averageAmount.doubleValue(),
```

`BigDecimal.doubleValue()` converts to a `double`, which is IEEE 754 binary floating-point. This can reintroduce precision artifacts at the API boundary. For example, `new BigDecimal("23.07").doubleValue()` happens to be exact, but `new BigDecimal("0.10").doubleValue()` is not exactly 0.1 in binary floating-point.

### The Fix

Return the BigDecimal directly (Jackson serialises it as a JSON number without floating-point artifacts) or use `.toPlainString()` for guaranteed string representation:

```java
// Option A: Return BigDecimal directly (Jackson handles it)
"totalAmount", totalAmount,
"averageAmount", averageAmount,

// Option B: Return as string for absolute precision
"totalAmount", totalAmount.toPlainString(),
"averageAmount", averageAmount.toPlainString(),
```

### When to Mention This

**Do NOT lead with this.** It is not a bug -- the current values happen to be exact after conversion. Mention it only if:

1. The interviewer asks "anything else you would improve?"
2. You finish the bug fix early and want to show depth
3. The interviewer probes on floating-point precision

**30-second talking point:**

> "One thing I would flag for a follow-up PR: the stats response calls doubleValue() on the BigDecimal results before returning them. That converts back to IEEE 754 binary floating-point, which can reintroduce precision artifacts for certain values. For a financial API, I would return the BigDecimal directly and let Jackson serialise it, or use toPlainString() if you need a string representation."

---

## Section 9: Money Storage Discussion

### Current Approach

The application uses `decimal(10,2)` in the database and `BigDecimal` in Java. For a single-currency supermarket POS system, this is correct and conventional.

### Alternative: Lowest Denomination (Pennies as BIGINT)

Companies like Stripe, Adyen, and Square store all monetary amounts as integers in the lowest denomination (pennies/cents). So 7.69 GBP becomes `769` (a `BIGINT`).

**Comparison:**

| Property | decimal(10,2) + BigDecimal | BIGINT pennies |
|----------|---------------------------|----------------|
| Storage | `7.69` | `769` |
| Division | Need explicit `RoundingMode` | Integer division -- need explicit rounding |
| Addition | No rounding issues | No rounding issues (integer arithmetic) |
| Display | Already human-readable | Need to divide by 100 at display boundary |
| Multi-currency | Need to change scale per currency (JPY=0, BHD=3) | Natural: 1 JPY = 1 unit, 7.69 GBP = 769 units |
| Bugs eliminated | None structurally | Eliminates BigDecimal.ONE vs ZERO (integer addition). Eliminates RoundingMode confusion on simple sums. |
| Industry | Traditional banking, accounting | Payment processors (Stripe, Adyen, Square) |

### How Pennies Would Have Prevented 2 of 3 Bugs

1. **Bug 1 (BigDecimal.ONE):** With integer arithmetic, the reduce identity would be `0` (int), not `BigDecimal.ONE`. Harder to accidentally type `1` because the type system does not have a `ONE` constant sitting there.

2. **Bug 2c (RoundingMode.DOWN):** Simple sums do not need rounding at all in integer arithmetic. The rounding question only arises during division (the average), and even then the requirement is more obvious because you are doing integer division.

3. **Bug 2a (++count):** This bug is unrelated to storage format -- it would still be possible with pennies.

### 30-Second Interview Talking Point

> "For a single-currency supermarket system, decimal(10,2) with BigDecimal is the right call -- it is the industry standard for traditional retail POS. An alternative used by payment processors like Stripe is to store amounts in lowest denomination as integers -- 769 instead of 7.69. That eliminates entire classes of rounding bugs because simple arithmetic stays in integer space. The trade-off is you need a display layer to convert back. For multi-currency systems, the integer approach is significantly simpler because different currencies have different decimal places."

---

## Section 10: Debugging Strategy

### Step-by-Step Approach

1. **Hit the two endpoints.** `/stats/STORE-001` and `/store/STORE-001`. Compare the numbers. Note that the total is off by +1.00 and the average is wrong.

2. **Go to the stats endpoint code.** Open `TransactionController.java`, find `getTransactionStats()`. Look at the stream reduce -- spot `BigDecimal.ONE`. Fix it.

3. **Follow the average calculation.** See the call to `calculateTotalAmount`. Open `Calculator.java`. Spot the `++count`, the wrong name, and `RoundingMode.DOWN`. Fix all three.

4. **Verify.** Hit the stats endpoint again. Confirm 23.07 and 7.69.

5. **Write tests.** Add `CalculatorTest` cases. Run them.

### What NOT to Do

- **Do NOT start with TDD.** You do not know what the bugs are yet. Writing tests before understanding the problem is wasted time in a timed exercise.
- **Do NOT use the debugger.** For two simple arithmetic bugs, reading the code is faster than setting breakpoints. A debugger is overkill here and costs setup time.
- **Do NOT start from the database.** The data in the DB is correct (the store endpoint proves that). The bugs are in the calculation layer.
- **Do NOT grep the entire codebase.** The interviewer told you the stats endpoint is wrong. Go directly there.

### Time Budget Breakdown

| Phase | Target | Max |
|-------|--------|-----|
| Read API output, note discrepancy | 30 sec | 1 min |
| Trace to stats endpoint code | 1 min | 2 min |
| Identify both bugs | 1 min | 3 min |
| Apply fixes + rename + guards | 2 min | 3 min |
| Verify fix via API | 1 min | 2 min |
| Write Calculator unit tests | 3-5 min | 7 min |
| **Total** | **8-10 min** | **18 min** |

**Risk:** Spending more than 15 minutes here. The bugs are the warm-up -- the Kafka consumer is the main deliverable. If you burn 20+ minutes on two straightforward bugs, the interviewer will question your prioritisation.

---

## Section 11: Follow-up Questions to Expect

After you fix the bugs, the interviewer may probe your understanding. Here are the likely questions with prepared answers.

---

### "Why HALF_EVEN over HALF_UP?"

> "Both are valid for rounding to nearest. The difference is tie-breaking on exact .5 boundaries. HALF_UP always rounds ties up, which creates a slight upward bias over many operations. HALF_EVEN rounds ties to the nearest even digit, which alternates between up and down, eliminating systematic bias. For aggregate statistics over millions of transactions, HALF_EVEN gives more accurate totals. It is the IEEE 754 default and the standard in financial systems. Python's decimal module also defaults to HALF_EVEN."

---

### "What if count is negative?"

> "A negative count means something has gone wrong upstream -- either a data corruption issue or a bug in how transactions are counted. I added a guard that throws IllegalArgumentException immediately. Failing fast is better than silently returning a negative average, which could propagate into dashboards and reports without anyone noticing."

---

### "Should money be stored differently?"

> "For a single-currency supermarket POS, decimal(10,2) with BigDecimal is the industry standard and the right choice. An alternative used by Stripe and Adyen is to store amounts as integers in the lowest denomination -- pennies. That eliminates rounding bugs in simple arithmetic but requires a display conversion layer. For multi-currency systems, the integer approach is significantly simpler because different currencies have different decimal places. For this system, the current approach is fine."

---

### "What about the doubleValue() in the response?"

> "Good catch. The stats endpoint calls doubleValue() before returning totalAmount and averageAmount. That converts BigDecimal back to IEEE 754 floating-point, which can reintroduce precision artifacts for certain decimal values. For a financial API, I would return the BigDecimal directly and let Jackson serialise it as a JSON number, or use toPlainString() for a string representation. It is not causing visible errors with the current data, but it is a latent precision risk."

---

### "How would you prevent this type of bug?"

> "Three layers:
> 1. **Code review.** The reduce identity and pre-increment bugs are both things that careful review would catch. The BigDecimal.ONE is especially suspicious because it is rare to use ONE as a reduce identity for addition.
> 2. **Unit tests.** A simple test with known inputs and expected outputs catches both bugs immediately. The fact that Calculator had no tests is the real process failure.
> 3. **Integration tests with known seed data.** Submit transactions with known amounts, hit the stats endpoint, assert exact values. This catches end-to-end calculation errors that unit tests on individual components might miss."

---

### "Would you write more tests?"

> "Yes. Beyond the Calculator unit tests I already wrote, I would add:
> - An integration test that hits /stats/STORE-001 after seeding 3 transactions at 7.69 each, and asserts totalAmount=23.07 and averageAmount=7.69.
> - HALF_EVEN tie-breaking tests to prove banker's rounding is active.
> - A single-transaction test (10.00, 1) that returns exactly 10.00 -- this is the most direct regression test for the ++count bug, because with the old code it would return 5.00."

---

### "What is the business impact of this bug?"

> "Two impacts. First, every store's revenue total is overstated by one pound. Across thousands of stores, that is thousands of pounds of phantom revenue that would fail reconciliation against bank settlements. Second, every store's average basket size is understated because of the ++count divide-by-n+1 error. If management uses these averages to decide on promotions or staffing, they are making decisions based on artificially low numbers. The average says 6.01 but reality is 7.69 -- that is a 22% error."

---

### "Is DOWN ever the right choice?"

> "Yes, in specific regulatory contexts. For example, some tax authorities (including HMRC for certain VAT calculations) mandate truncation rather than rounding. The key question is: does a regulation or contract specify the rounding mode? If yes, use what they say. If no, default to HALF_EVEN for its statistical unbiasedness. In this case, we are computing display statistics, not tax amounts, so HALF_EVEN is clearly correct."

---

### "Why did you add a JavaDoc comment?"

> "Because this is a financial calculation utility. The next developer who touches this method needs to know: what it returns (average, not total), what scale it uses (2 decimal places), what rounding mode it uses (HALF_EVEN), and what happens on edge cases (zero returns 0.00, negative throws). Financial code should be self-documenting -- ambiguity in money calculations is how bugs like this get introduced."

---

### "What is the difference between `++count` and `count++`?"

> "`++count` is pre-increment: it increments the variable BEFORE returning the value. `count++` is post-increment: it returns the current value THEN increments. In this context, `++count` turns 3 into 4 before the division. `count++` would have used 3 for the division but then incremented the local variable (which would be unused). So `count++` would actually produce the correct result by accident, but it is still wrong practice because the side effect is confusing and unnecessary. The correct code is just `count` -- no increment at all."

---

### "How does this scale if there are millions of transactions per store?"

> "The current implementation loads ALL transactions into memory with `getTransactionsByStore()` and then streams over them. For millions of transactions, that is an OutOfMemoryError waiting to happen. The correct approach is to push the aggregation down to the database: `SELECT COUNT(*), SUM(total_amount) FROM transactions WHERE store_id = ?`. Then the average is computed in Java from the pre-aggregated sum and count. That is O(1) memory regardless of transaction count. But that is a separate conversation from the bug fix -- I would fix the bugs first, then raise the scalability concern."

---

### "Why not just use `double` instead of BigDecimal for money?"

> "Because double is IEEE 754 binary floating-point, and most decimal fractions (like 0.10) cannot be represented exactly in binary. Try `0.1 + 0.2` in Java -- you get `0.30000000000000004`. For money, where every penny must be exact, that is unacceptable. BigDecimal stores the value as an unscaled integer plus a scale, so `7.69` is stored as `769` with scale 2. No precision loss. The trade-off is performance -- BigDecimal is heap-allocated and slower than primitive double. For financial calculations, correctness always wins over performance."

---

## Pattern Recognition Coda

Both bugs are variants of the same anti-pattern: **off-by-one in disguise**.

- `BigDecimal.ONE` instead of `BigDecimal.ZERO`: off by one in the seed value
- `++count` instead of `count`: off by one in the divisor

When a financial calculation is wrong by a small, constant amount (not a percentage, not a floating-point artifact, but exactly +1.00), look for:
1. Wrong identity/seed values in folds/reduces
2. Off-by-one in loop bounds or pre/post increment operators
3. Fencepost errors (inclusive vs exclusive ranges)

Say this out loud during the interview. It demonstrates systematic pattern recognition rather than just "I saw the bug and fixed it."
