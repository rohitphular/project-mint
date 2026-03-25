package com.vega.techtest.controller;

import static com.vega.techtest.utils.Calculator.calculateTotalAmount;

import com.vega.techtest.dto.TransactionRequest;
import com.vega.techtest.dto.TransactionResponse;
import com.vega.techtest.service.TransactionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;

import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;
    private final MeterRegistry meterRegistry;

    // Counters
    private final Counter transactionSubmissionCounter;
    private final Counter transactionRetrievalCounter;
    private final Counter transactionErrorCounter;

    // Timers
    private final Timer transactionSubmissionTimer;
    private final Timer transactionRetrievalTimer;

    // Distribution summaries
    private final DistributionSummary transactionAmountSummary;
    private final DistributionSummary transactionItemCountSummary;

    @Autowired
    public TransactionController(TransactionService transactionService,
                                 MeterRegistry meterRegistry) {
        this.transactionService = transactionService;
        this.meterRegistry = meterRegistry;

        // Initialize counters
        this.transactionSubmissionCounter = Counter.builder("transaction_submissions_total")
                .description("Total number of transaction submissions via REST API")
                .register(meterRegistry);
        this.transactionRetrievalCounter = Counter.builder("transaction_retrievals_total")
                .description("Total number of transaction retrievals via REST API")
                .register(meterRegistry);
        this.transactionErrorCounter = Counter.builder("transaction_errors_total")
                .description("Total number of transaction processing errors")
                .register(meterRegistry);

        // Initialize timers
        this.transactionSubmissionTimer = Timer.builder("transaction_submission_duration")
                .description("Time taken to process transaction submissions")
                .register(meterRegistry);
        this.transactionRetrievalTimer = Timer.builder("transaction_retrieval_duration")
                .description("Time taken to retrieve transactions")
                .register(meterRegistry);

        // Initialize distribution summaries
        this.transactionAmountSummary = DistributionSummary.builder("transaction_amount")
                .description("Distribution of transaction amounts")
                .baseUnit("GBP")
                .register(meterRegistry);
        this.transactionItemCountSummary = DistributionSummary.builder("transaction_item_count")
                .description("Distribution of number of items per transaction")
                .baseUnit("items")
                .register(meterRegistry);
    }

    /**
     * Legacy REST endpoint for supermarket tills to submit transactions
     * This simulates the existing system that tills currently use
     */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submitTransaction(@RequestBody TransactionRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            logger.info("Received transaction submission from till: {}", request.getTillId());

            TransactionResponse response = transactionService.processTransaction(request);

            // Record metrics
            transactionSubmissionCounter.increment();
            sample.stop(transactionSubmissionTimer);

            // Record transaction amount and item count
            transactionAmountSummary.record(response.getTotalAmount().doubleValue());
            transactionItemCountSummary.record(response.getItems().size());

            // Record tagged metrics for store and till
            Counter.builder("transaction_submissions_by_store")
                    .tag("store_id", request.getStoreId())
                    .description("Transaction submissions by store")
                    .register(meterRegistry)
                    .increment();

            Counter.builder("transaction_submissions_by_till")
                    .tag("till_id", request.getTillId())
                    .description("Transaction submissions by till")
                    .register(meterRegistry)
                    .increment();

            Counter.builder("transaction_submissions_by_payment_method")
                    .tag("payment_method", request.getPaymentMethod())
                    .description("Transaction submissions by payment method")
                    .register(meterRegistry)
                    .increment();

            logger.info("Successfully processed transaction: {}", response.getTransactionId());

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Transaction processed successfully",
                    "transactionId", response.getTransactionId(),
                    "timestamp", response.getTransactionTimestamp()
            ));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid transaction request: {}", e.getMessage());
            transactionErrorCounter.increment();
            sample.stop(transactionSubmissionTimer);

            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Invalid transaction data",
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Error processing transaction", e);
            transactionErrorCounter.increment();
            sample.stop(transactionSubmissionTimer);

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to process transaction",
                    "error", "Internal server error"
            ));
        }
    }

    /**
     * Get transaction by ID
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<Object> getTransaction(@PathVariable String transactionId) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            Optional<TransactionResponse> transaction = transactionService.getTransactionById(transactionId);
            transactionRetrievalCounter.increment();
            sample.stop(transactionRetrievalTimer);

            if (transaction.isPresent()) {
                return ResponseEntity.ok(transaction.get());
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            logger.error("Error retrieving transaction: {}", transactionId, e);
            transactionErrorCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to retrieve transaction",
                    "error", "Internal server error"
            ));
        }
    }

    /**
     * Get transactions by store
     */
    @GetMapping("/store/{storeId}")
    public ResponseEntity<Object> getTransactionsByStore(@PathVariable String storeId) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<TransactionResponse> transactions = transactionService.getTransactionsByStore(storeId);
            transactionRetrievalCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.ok(Map.of(
                    "storeId", storeId,
                    "count", transactions.size(),
                    "transactions", transactions
            ));
        } catch (Exception e) {
            logger.error("Error retrieving transactions for store: {}", storeId, e);
            transactionErrorCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to retrieve transactions",
                    "error", "Internal server error"
            ));
        }
    }

    /**
     * Get transactions by customer
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<Object> getTransactionsByCustomer(@PathVariable String customerId) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<TransactionResponse> transactions = transactionService.getTransactionsByCustomer(customerId);
            transactionRetrievalCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.ok(Map.of(
                    "customerId", customerId,
                    "count", transactions.size(),
                    "transactions", transactions
            ));
        } catch (Exception e) {
            logger.error("Error retrieving transactions for customer: {}", customerId, e);
            transactionErrorCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to retrieve transactions",
                    "error", "Internal server error"
            ));
        }
    }

    /**
     * Get transactions by till
     */
    @GetMapping("/till/{tillId}")
    public ResponseEntity<Object> getTransactionsByTill(@PathVariable String tillId) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<TransactionResponse> transactions = transactionService.getTransactionsByTill(tillId);
            transactionRetrievalCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.ok(Map.of(
                    "tillId", tillId,
                    "count", transactions.size(),
                    "transactions", transactions
            ));
        } catch (Exception e) {
            logger.error("Error retrieving transactions for till: {}", tillId, e);
            transactionErrorCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to retrieve transactions",
                    "error", "Internal server error"
            ));
        }
    }

    /**
     * Get transactions by date range
     */
    @GetMapping("/date-range")
    public ResponseEntity<Object> getTransactionsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime endDate) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<TransactionResponse> transactions = transactionService.getTransactionsByDateRange(startDate, endDate);
            transactionRetrievalCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.ok(Map.of(
                    "startDate", startDate,
                    "endDate", endDate,
                    "count", transactions.size(),
                    "transactions", transactions
            ));
        } catch (Exception e) {
            logger.error("Error retrieving transactions by date range", e);
            transactionErrorCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to retrieve transactions",
                    "error", "Internal server error"
            ));
        }
    }

    /**
     * Create sample transactions for testing (matches user report scenario)
     */
    @PostMapping("/sample")
    public ResponseEntity<Map<String, Object>> createSampleTransaction() {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Create a sample supermarket transaction
            TransactionRequest request = new TransactionRequest();
            request.setTransactionId("TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            request.setCustomerId("CUST-" + (int) (Math.random() * 99999));
            request.setStoreId("STORE-001");
            request.setTillId("TILL-" + (int) (Math.random() * 10 + 1));
            request.setPaymentMethod(Math.random() > 0.5 ? "card" : "cash");
            request.setTotalAmount(new java.math.BigDecimal("7.69"));
            request.setCurrency("GBP");
            request.setTimestamp(ZonedDateTime.now());

            // Add sample items
            request.setItems(List.of(
                    new com.vega.techtest.dto.TransactionItemRequest("Milk", "MILK001", new java.math.BigDecimal("2.50"), 1, "Dairy"),
                    new com.vega.techtest.dto.TransactionItemRequest("Bread", "BREAD001", new java.math.BigDecimal("1.20"), 1, "Bakery"),
                    new com.vega.techtest.dto.TransactionItemRequest("Coffee", "COFFEE001", new java.math.BigDecimal("3.99"), 1, "Beverages")
            ));

            TransactionResponse response = transactionService.processTransaction(request);
            transactionSubmissionCounter.increment();
            sample.stop(transactionSubmissionTimer);

            // Record metrics for sample transaction
            transactionAmountSummary.record(response.getTotalAmount().doubleValue());
            transactionItemCountSummary.record(response.getItems().size());

            logger.info("Created sample transaction: {}", response.getTransactionId());

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Sample transaction created",
                    "transaction", response
            ));

        } catch (Exception e) {
            logger.error("Error creating sample transaction", e);
            transactionErrorCounter.increment();
            sample.stop(transactionSubmissionTimer);

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to create sample transaction",
                    "error", e.getMessage()
            ));
        }
    }


    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "transaction-service",
                "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }

    /**
     * Get transaction statistics for a store
     */
    @GetMapping("/stats/{storeId}")
    public ResponseEntity<Map<String, Object>> getTransactionStats(@PathVariable String storeId) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            logger.info("Calculating transaction statistics for store: {}", storeId);

            List<TransactionResponse> transactions = transactionService.getTransactionsByStore(storeId);

            if (transactions.isEmpty()) {
                logger.warn("No transactions found for store: {}", storeId);
                return ResponseEntity.ok(Map.of(
                        "storeId", storeId,
                        "message", "No transactions found for this store",
                        "totalTransactions", 0,
                        "totalAmount", 0.0,
                        "averageAmount", 0.0
                ));
            }

            // Calculate statistics
            int totalTransactions = transactions.size();
            BigDecimal totalAmount = transactions.stream()
                    .map(TransactionResponse::getTotalAmount)
                    .reduce(BigDecimal.ONE, BigDecimal::add);

            BigDecimal averageAmount = calculateTotalAmount(totalAmount, totalTransactions);

            logger.info("Store {} statistics - Total transactions: {}, Total amount: {}, Average amount: {}",
                    storeId, totalTransactions, totalAmount, averageAmount);

            transactionRetrievalCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.ok(Map.of(
                    "storeId", storeId,
                    "totalTransactions", totalTransactions,
                    "totalAmount", totalAmount.doubleValue(),
                    "averageAmount", averageAmount.doubleValue(),
                    "calculationNote", "Average calculated as total amount divided by transaction count"
            ));

        } catch (Exception e) {
            logger.error("Error calculating transaction statistics for store: {}", storeId, e);
            transactionErrorCounter.increment();
            sample.stop(transactionRetrievalTimer);

            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to calculate transaction statistics",
                    "error", "Internal server error"
            ));
        }
    }
} 