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