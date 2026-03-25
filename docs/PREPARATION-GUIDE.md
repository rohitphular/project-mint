# Preparation Guide

This guide will help you familiarise yourself with the code before the live coding session.

## Project Context

This is a transaction processing system for supermarkets. Point-of-sale terminals (tills) send transactions that are stored and used to calculate statistics.

### Tech Stack

- **Backend**: Spring Boot 3.2 with Java 21
- **Database**: PostgreSQL
- **Messaging**: Apache Kafka
- **Build**: Gradle
- **Containers**: Docker Compose

## Code Structure

```
src/main/java/com/vega/techtest/
├── TechTestApplication.java        # Entry point
├── controller/
│   └── TransactionController.java  # REST endpoints
├── service/
│   └── TransactionService.java     # Business logic
├── repository/
│   └── TransactionRepository.java  # Data access (JPA)
├── entity/
│   ├── TransactionEntity.java      # Transaction model
│   └── TransactionItemEntity.java  # Transaction items
├── dto/
│   ├── TransactionRequest.java     # REST API input
│   ├── TransactionResponse.java    # REST API output
│   └── KafkaTransactionEvent.java  # Kafka message format
└── utils/
    └── Calculator.java             # Calculation utilities
```

## Key Files to Review

### 1. TransactionController.java
**Location**: `src/main/java/com/vega/techtest/controller/`

Contains all REST endpoints:
- `POST /api/transactions/submit` - Receives transactions
- `GET /api/transactions/{id}` - Query by ID
- `GET /api/transactions/stats/{storeId}` - Statistics by store

Familiarise yourself with how the endpoints are structured and what they do.

### 2. TransactionService.java
**Location**: `src/main/java/com/vega/techtest/service/`

Main business logic. Understand how it processes and persists transactions.

### 3. Entities and DTOs
**Location**: `src/main/java/com/vega/techtest/entity/` and `dto/`

- `TransactionEntity` - How a transaction is stored in the database
- `TransactionRequest/Response` - REST API format
- `KafkaTransactionEvent` - Kafka event format

Note the differences between these formats.

### 4. docker-compose.yml
**Location**: Project root

Review which services are started and on which ports:
- PostgreSQL (5432)
- Kafka (9092)
- Kafka UI (8081) - useful for inspecting messages

## Concepts to Understand

### Current Data Flow

```
Till (POS) ──POST──> REST API ──> Service ──> Database
```

### Data Flow with Kafka

```
Till (POS) ──message──> Kafka Topic ──> Consumer ──> Database
```

### Kafka Message Format

```json
{
  "eventId": "uuid",
  "eventType": "TRANSACTION_CREATED",
  "eventTimestamp": "2025-06-27T12:00:00.000Z",
  "source": "till-system",
  "version": "1.0",
  "data": {
    "transactionId": "TXN-12345678",
    "storeId": "STORE-001",
    "tillId": "TILL-1",
    "totalAmount": 25.50,
    "items": [...]
  }
}
```

Note that the format differs from the REST API (wrapper with metadata, different data types).

## Pre-Interview Checklist

- [ ] Project compiles without errors (`./gradlew build`)
- [ ] Infrastructure starts correctly (`docker-compose up -d`)
- [ ] Application runs (`./gradlew bootRun`)
- [ ] Health check responds (`curl localhost:8080/api/transactions/health`)
- [ ] You've reviewed the key files mentioned above
- [ ] You understand the basic data flow

## Useful Tools

### Kafka UI
Access http://localhost:8081 to view messages in the `transactions` topic.

### Postman Collection
The `/postman` directory contains a collection with all endpoints ready to test.

