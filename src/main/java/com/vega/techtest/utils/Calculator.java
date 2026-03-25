package com.vega.techtest.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Calculator {

    public static BigDecimal calculateTotalAmount(BigDecimal totalAmount, int count) {
        return totalAmount.divide(BigDecimal.valueOf(++count), 2, RoundingMode.DOWN);
    }
}
