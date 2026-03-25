package com.vega.techtest.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "transaction_items")
public class TransactionItemEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", referencedColumnName = "id")
    private TransactionEntity transaction;
    
    @Column(name = "product_name", nullable = false)
    private String productName;
    
    @Column(name = "product_code")
    private String productCode;
    
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;
    
    @Column(name = "quantity", nullable = false)
    private Integer quantity;
    
    @Column(name = "total_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;
    
    @Column(name = "category")
    private String category;
    
    // Constructors
    public TransactionItemEntity() {}
    
    public TransactionItemEntity(TransactionEntity transaction, String productName,
                               String productCode, BigDecimal unitPrice, Integer quantity, String category) {
        this.transaction = transaction;
        this.productName = productName;
        this.productCode = productCode;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.category = category;
        this.totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public TransactionEntity getTransaction() {
        return transaction;
    }
    
    public void setTransaction(TransactionEntity transaction) {
        this.transaction = transaction;
    }
    
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