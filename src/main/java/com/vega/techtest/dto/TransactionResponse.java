package com.vega.techtest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

public class TransactionResponse {

    private String transactionId;
    private String customerId;
    private String storeId;
    private String tillId;
    private String paymentMethod;
    private BigDecimal totalAmount;
    private String currency;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    private ZonedDateTime transactionTimestamp;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    private ZonedDateTime createdAt;

    private String status;
    private List<TransactionItemResponse> items;

    // Constructors
    public TransactionResponse() {
    }

    public TransactionResponse(String transactionId, String customerId, String storeId,
                               String tillId, String paymentMethod, BigDecimal totalAmount,
                               String currency, ZonedDateTime transactionTimestamp, String status) {
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.storeId = storeId;
        this.tillId = tillId;
        this.paymentMethod = paymentMethod;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.transactionTimestamp = transactionTimestamp;
        this.status = status;
        this.createdAt = ZonedDateTime.now();
    }

    // Getters and Setters
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public String getTillId() {
        return tillId;
    }

    public void setTillId(String tillId) {
        this.tillId = tillId;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public ZonedDateTime getTransactionTimestamp() {
        return transactionTimestamp;
    }

    public void setTransactionTimestamp(ZonedDateTime transactionTimestamp) {
        this.transactionTimestamp = transactionTimestamp;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(ZonedDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<TransactionItemResponse> getItems() {
        return items;
    }

    public void setItems(List<TransactionItemResponse> items) {
        this.items = items;
    }
} 