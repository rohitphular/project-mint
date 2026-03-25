package com.vega.techtest.dto;

import java.math.BigDecimal;

public class TransactionItemResponse {

    private String productName;
    private String productCode;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal totalPrice;
    private String category;

    // Constructors
    public TransactionItemResponse() {
    }

    public TransactionItemResponse(String productName, String productCode,
                                   BigDecimal unitPrice, Integer quantity, BigDecimal totalPrice) {
        this.productName = productName;
        this.productCode = productCode;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
    }

    public TransactionItemResponse(String productName, String productCode,
                                   BigDecimal unitPrice, Integer quantity, BigDecimal totalPrice,
                                   String category) {
        this(productName, productCode, unitPrice, quantity, totalPrice);
        this.category = category;
    }

    // Getters and Setters
    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
} 