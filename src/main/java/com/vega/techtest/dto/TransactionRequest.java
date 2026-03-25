package com.vega.techtest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

public class TransactionRequest {

    private String transactionId;
    private String customerId;
    private String storeId;
    private String tillId;
    private String paymentMethod;
    private BigDecimal totalAmount;
    private String currency = "GBP";

    private ZonedDateTime timestamp;

    private List<TransactionItemRequest> items;

    // Constructors
    public TransactionRequest() {
    }

    public TransactionRequest(String transactionId, String customerId, String storeId,
                              String tillId, String paymentMethod, BigDecimal totalAmount) {
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.storeId = storeId;
        this.tillId = tillId;
        this.paymentMethod = paymentMethod;
        this.totalAmount = totalAmount;
        this.timestamp = ZonedDateTime.now();
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

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public List<TransactionItemRequest> getItems() {
        return items;
    }

    public void setItems(List<TransactionItemRequest> items) {
        this.items = items;
    }
} 