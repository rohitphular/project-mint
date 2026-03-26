package com.vega.techtest.consumer;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.techtest.dto.KafkaTransactionEvent;
import com.vega.techtest.dto.TransactionItemRequest;
import com.vega.techtest.dto.TransactionRequest;
import com.vega.techtest.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TransactionKafkaConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TransactionKafkaConsumer.class);
    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    public TransactionKafkaConsumer(TransactionService transactionService, ObjectMapper objectMapper) {
        this.transactionService = transactionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "transactions", groupId = "transaction-group")
    public void consumer(String message) throws Exception {
        logger.info("Received Kafka message: {}", message);

        try {
            JsonNode root = objectMapper.readTree(message);

            TransactionRequest transactionRequest;
            if (root.has("eventId") && root.has("data")) {
                // Format B: Envelope format from Python producer
                KafkaTransactionEvent event = objectMapper.treeToValue(root,
                        KafkaTransactionEvent.class);
                Map<String, Object> data = event.getData();
                if (data == null) {
                    logger.warn("Event data is null for eventId: {}, skipping",
                            event.getEventId());
                    return;
                }
                transactionRequest = mapToTransactionRequest(data);
            } else if (root.has("transactionId")) {
                // Format A: Flat format from seed data / legacy producers
                transactionRequest = mapFlatFormatToTransactionRequest(root);
            } else {
                logger.warn("Unrecognized message format, skipping");
                return;
            }

            /* event-data should be transformed to TransactionRequest */
            transactionService.processTransaction(transactionRequest);

            logger.info("Successfully processed transaction with ID: {}", transactionRequest.getTransactionId());

        } catch (Exception e) {
            /* Handle JsonProcessingException, IllegalArgumentException appropriately */
            logger.error("Failed to deserialize Kafka message: {}", e.getMessage());
            throw e;
        }
    }

    private TransactionRequest mapToTransactionRequest(Map<String, Object> eventData) {
        TransactionRequest transactionRequest = new TransactionRequest();
        transactionRequest.setTransactionId((String) eventData.get("transactionId"));
        transactionRequest.setCustomerId((String) eventData.get("customerId"));
        transactionRequest.setStoreId((String) eventData.get("storeId"));
        transactionRequest.setTillId((String) eventData.get("tillId"));
        transactionRequest.setPaymentMethod((String) eventData.get("paymentMethod"));
        transactionRequest.setCurrency((String) eventData.get("currency"));

        Object totalAmountObj = eventData.get("totalAmount");
        if (totalAmountObj instanceof Number number) {
            transactionRequest.setTotalAmount(BigDecimal.valueOf(number.doubleValue()));
        }

        Object timestampObj = eventData.get("timestamp");
        if (timestampObj instanceof String timestampStr) {
            transactionRequest.setTimestamp(ZonedDateTime.parse(timestampStr));
        }

        Object itemsObject = eventData.get("items");
        if (itemsObject instanceof List<?> rawItems) {
            List<TransactionItemRequest> items = rawItems
                    .stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> mapToTransactionItems((Map<String, Object>) item))
                    .toList();

            transactionRequest.setItems(items);
        }

        return transactionRequest;
    }

    private static TransactionItemRequest mapToTransactionItems(Map<String, Object> item) {
        TransactionItemRequest itemRequest = new TransactionItemRequest();
        itemRequest.setProductName((String) item.get("productName"));
        itemRequest.setProductCode((String) item.get("productCode"));
        itemRequest.setCategory((String) item.get("category"));

        Object unitPriceObj = item.get("unitPrice");
        if (unitPriceObj instanceof Number unitPriceNum) {
            itemRequest.setUnitPrice(BigDecimal.valueOf(unitPriceNum.doubleValue()));
        }

        Object quantityObj = item.get("quantity");
        if (quantityObj instanceof Number quantityNum) {
            itemRequest.setQuantity(quantityNum.intValue());
        }

        return itemRequest;
    }

    private TransactionRequest mapFlatFormatToTransactionRequest(JsonNode root) {
        TransactionRequest request = new TransactionRequest();

        request.setTransactionId(root.path("transactionId").asText(null));
        request.setCustomerId(root.path("customerId").asText(null));
        request.setStoreId(root.path("storeId").asText(null));
        request.setPaymentMethod(root.path("paymentMethod").asText(null));
        // Format A has no tillId or currency
        request.setCurrency("GBP");

        // CRITICAL: Format A uses "total", NOT "totalAmount"
        JsonNode totalNode = root.path("total");
        if (totalNode.isNumber()) {
            request.setTotalAmount(BigDecimal.valueOf(totalNode.asDouble()));
        }

        // Timestamp
        String ts = root.path("timestamp").asText(null);
        if (ts != null) {
            try {
                request.setTimestamp(ZonedDateTime.parse(ts));
            } catch (DateTimeParseException e) {
                logger.warn("Invalid timestamp '{}' for txn {}, skipping timestamp",
                        ts, request.getTransactionId());
            }
        }

        // Items: Format A uses "name" and "price", NOT "productName" and "unitPrice"
        JsonNode itemsNode = root.path("items");
        if (itemsNode.isArray()) {
            List<TransactionItemRequest> items = new ArrayList<>();
            for (JsonNode itemNode : itemsNode) {
                TransactionItemRequest item = new TransactionItemRequest();
                item.setProductName(itemNode.path("name").asText(null));
                // No productCode in Format A
                if (itemNode.path("price").isNumber()) {
                    item.setUnitPrice(BigDecimal.valueOf(itemNode.path("price").asDouble()));
                }
                if (itemNode.path("quantity").isNumber()) {
                    item.setQuantity(itemNode.path("quantity").asInt());
                }
                // No category in Format A
                items.add(item);
            }
            request.setItems(items);
        }

        return request;
    }

}
