package com.vega.techtest.repository;

import com.vega.techtest.entity.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    /**
     * Find transaction by transaction ID
     */
    Optional<TransactionEntity> findByTransactionId(String transactionId);

    /**
     * Find all transactions for a specific store
     */
    List<TransactionEntity> findByStoreIdOrderByTransactionTimestampDesc(String storeId);

    /**
     * Find all transactions for a specific customer
     */
    List<TransactionEntity> findByCustomerIdOrderByTransactionTimestampDesc(String customerId);

    /**
     * Find all transactions for a specific till
     */
    List<TransactionEntity> findByTillIdOrderByTransactionTimestampDesc(String tillId);

    /**
     * Find transactions within a date range
     */
    @Query("SELECT t FROM TransactionEntity t WHERE t.transactionTimestamp BETWEEN :startDate AND :endDate ORDER BY t.transactionTimestamp DESC")
    List<TransactionEntity> findTransactionsByDateRange(@Param("startDate") ZonedDateTime startDate,
                                                        @Param("endDate") ZonedDateTime endDate);

    /**
     * Find transactions by payment method
     */
    List<TransactionEntity> findByPaymentMethodOrderByTransactionTimestampDesc(String paymentMethod);

    /**
     * Count transactions by store
     */
    @Query("SELECT t.storeId, COUNT(t) FROM TransactionEntity t GROUP BY t.storeId")
    List<Object[]> countTransactionsByStore();

    /**
     * Get total sales amount by store
     */
    @Query("SELECT t.storeId, SUM(t.totalAmount) FROM TransactionEntity t GROUP BY t.storeId")
    List<Object[]> getTotalSalesByStore();

    /**
     * Check if transaction ID already exists
     */
    boolean existsByTransactionId(String transactionId);
} 