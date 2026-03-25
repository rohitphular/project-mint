package com.vega.techtest.service;

import com.vega.techtest.dto.TransactionRequest;
import com.vega.techtest.dto.TransactionResponse;
import com.vega.techtest.dto.TransactionItemRequest;
import com.vega.techtest.dto.TransactionItemResponse;
import com.vega.techtest.entity.TransactionEntity;
import com.vega.techtest.entity.TransactionItemEntity;
import com.vega.techtest.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;

    @Autowired
    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Process and save a new transaction from the legacy REST API
     */
    @Transactional
    public TransactionResponse processTransaction(TransactionRequest request) {
        logger.info("Processing transaction request: {}", request.getTransactionId());

        // Generate transaction ID if not provided
        String transactionId = request.getTransactionId();
        if (transactionId == null || transactionId.trim().isEmpty()) {
            transactionId = generateTransactionId();
            request.setTransactionId(transactionId);
        }

        // Check for duplicate transaction ID
        if (transactionRepository.existsByTransactionId(transactionId)) {
            throw new IllegalArgumentException("Transaction ID already exists: " + transactionId);
        }

        // Set timestamp if not provided
        if (request.getTimestamp() == null) {
            request.setTimestamp(ZonedDateTime.now());
        }

        // Validate request
        validateTransactionRequest(request);

        // Create transaction entity
        TransactionEntity transaction = new TransactionEntity(
                transactionId,
                request.getCustomerId(),
                request.getStoreId(),
                request.getTillId(),
                request.getPaymentMethod(),
                request.getTotalAmount()
        );

        transaction.setCurrency(request.getCurrency());
        transaction.setTransactionTimestamp(request.getTimestamp());

        // Create transaction items if provided
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            List<TransactionItemEntity> items = request.getItems().stream()
                    .map(itemRequest -> new TransactionItemEntity(
                            transaction,
                            itemRequest.getProductName(),
                            itemRequest.getProductCode(),
                            itemRequest.getUnitPrice(),
                            itemRequest.getQuantity(),
                            itemRequest.getCategory()
                    ))
                    .collect(Collectors.toList());

            transaction.setItems(items);
        }

        // Save transaction
        TransactionEntity savedTransaction = transactionRepository.save(transaction);
        logger.info("Successfully saved transaction: {}", transactionId);

        return convertToResponse(savedTransaction);
    }

    /**
     * Get transaction by ID
     */
    public Optional<TransactionResponse> getTransactionById(String transactionId) {
        return transactionRepository.findByTransactionId(transactionId)
                .map(this::convertToResponse);
    }

    /**
     * Get all transactions for a store
     */
    public List<TransactionResponse> getTransactionsByStore(String storeId) {
        return transactionRepository.findByStoreIdOrderByTransactionTimestampDesc(storeId)
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get all transactions for a customer
     */
    public List<TransactionResponse> getTransactionsByCustomer(String customerId) {
        return transactionRepository.findByCustomerIdOrderByTransactionTimestampDesc(customerId)
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get all transactions for a till
     */
    public List<TransactionResponse> getTransactionsByTill(String tillId) {
        return transactionRepository.findByTillIdOrderByTransactionTimestampDesc(tillId)
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get transactions within a date range
     */
    public List<TransactionResponse> getTransactionsByDateRange(ZonedDateTime startDate, ZonedDateTime endDate) {
        return transactionRepository.findTransactionsByDateRange(startDate, endDate)
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Generate a unique transaction ID
     */
    private String generateTransactionId() {
        return "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * Validate transaction request
     */
    private void validateTransactionRequest(TransactionRequest request) {
        if (request.getStoreId() == null || request.getStoreId().trim().isEmpty()) {
            throw new IllegalArgumentException("Store ID is required");
        }

        if (request.getPaymentMethod() == null || request.getPaymentMethod().trim().isEmpty()) {
            throw new IllegalArgumentException("Payment method is required");
        }

        if (request.getTotalAmount() == null || request.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Total amount must be greater than zero");
        }

        // Validate items if provided
        if (request.getItems() != null) {
            BigDecimal calculatedTotal = request.getItems().stream()
                    .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (calculatedTotal.compareTo(request.getTotalAmount()) != 0) {
                logger.warn("Calculated total ({}) doesn't match provided total ({})",
                        calculatedTotal, request.getTotalAmount());
            }
        }
    }

    /**
     * Convert entity to response DTO
     */
    private TransactionResponse convertToResponse(TransactionEntity entity) {
        TransactionResponse response = new TransactionResponse(
                entity.getTransactionId(),
                entity.getCustomerId(),
                entity.getStoreId(),
                entity.getTillId(),
                entity.getPaymentMethod(),
                entity.getTotalAmount(),
                entity.getCurrency(),
                entity.getTransactionTimestamp(),
                entity.getStatus()
        );

        response.setCreatedAt(entity.getCreatedAt());

        // Convert items if present
        if (entity.getItems() != null && !entity.getItems().isEmpty()) {
            List<TransactionItemResponse> itemResponses = entity.getItems().stream()
                    .map(item -> new TransactionItemResponse(
                            item.getProductName(),
                            item.getProductCode(),
                            item.getUnitPrice(),
                            item.getQuantity(),
                            item.getTotalPrice(),
                            item.getCategory()
                    ))
                    .collect(Collectors.toList());

            response.setItems(itemResponses);
        }

        return response;
    }
} 