package com.vega.techtest.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Calculator {

    public static BigDecimal calculateAverageAmount(BigDecimal totalAmount, int count) {
        if (count == 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        if (count < 0) throw new IllegalArgumentException("Count cannot be negative");
        return totalAmount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN);
    }
}
