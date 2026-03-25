package com.vega.techtest.dto;

import java.math.BigDecimal;

public class TransactionItemRequest {

    private String productName;
    private String productCode;
    private BigDecimal unitPrice;
    private Integer quantity;
    private String category;

    // Constructors
    public TransactionItemRequest() {
    }

    public TransactionItemRequest(String productName, String productCode,
                                  BigDecimal unitPrice, Integer quantity) {
        this.productName = productName;
        this.productCode = productCode;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public TransactionItemRequest(String productName, String productCode,
                                  BigDecimal unitPrice, Integer quantity, String category) {
        this(productName, productCode, unitPrice, quantity);
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
} 