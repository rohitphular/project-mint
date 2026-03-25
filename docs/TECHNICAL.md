# Technical Details

## System Overview

This is a supermarket transaction processing system. Point-of-sale terminals (tills) send transactions that are stored in a database and used to calculate statistics.

### Architecture

```
┌─────────────┐     POST      ┌─────────────┐     ┌─────────────┐
│    Till     │──────────────>│  REST API   │────>│  Database   │
│  Simulator  │               │             │     │ (PostgreSQL)│
└─────────────┘               └─────────────┘     └─────────────┘

┌─────────────┐    publish    ┌─────────────┐
│   Kafka     │──────────────>│    Kafka    │
│  Producer   │               │    Topic    │
└─────────────┘               └─────────────┘
```

### Components

- **REST API** - Tills send transactions via HTTP
- **Database** - PostgreSQL stores transaction data
- **Statistics** - Calculated from database records
- **Till Simulator** - Generates REST transactions (runs in Docker)
- **Kafka Producer** - Publishes event messages (runs in Docker)

## Technical Stack

- **Backend**: Spring Boot 3.2 (Java 21)
- **Database**: PostgreSQL
- **Message Broker**: Apache Kafka
- **Monitoring**: Grafana + Prometheus
- **Observability**: OpenTelemetry
- **Containerization**: Docker & Docker Compose
- **API Testing**: Postman Collections

## REST API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/transactions/submit` | Submit a transaction |
| GET | `/api/transactions/{id}` | Get transaction by ID |
| GET | `/api/transactions/store/{storeId}` | Get transactions by store |
| GET | `/api/transactions/stats/{storeId}` | Get store statistics |
| GET | `/api/transactions/health` | Health check |

## Kafka Message Format

The Kafka producer sends transaction events to the `transactions` topic:

```json
{
  "eventId": "uuid",
  "eventType": "TRANSACTION_CREATED",
  "eventTimestamp": "2025-06-27T12:00:00.000Z",
  "source": "till-system",
  "version": "1.0",
  "data": {
    "transactionId": "TXN-12345678",
    "customerId": "CUST-12345",
    "storeId": "STORE-001",
    "tillId": "TILL-1",
    "paymentMethod": "card",
    "totalAmount": 25.50,
    "currency": "GBP",
    "timestamp": "2025-06-27T12:00:00.000Z",
    "items": [
      {
        "productName": "Milk",
        "productCode": "MILK001",
        "unitPrice": 2.50,
        "quantity": 2,
        "category": "Dairy"
      },
      {
        "productName": "Bread",
        "productCode": "BREAD001",
        "unitPrice": 1.20,
        "quantity": 1,
        "category": "Bakery"
      }
    ]
  }
}
```

**Key Differences from REST API:**

- Transaction data is wrapped in an event envelope with metadata
- `totalAmount` is a `float` instead of `BigDecimal`
- Timestamps are ISO strings instead of `ZonedDateTime`
- Event metadata includes `eventId`, `eventType`, `source`, and `version`

## Infrastructure Services

| Service | Port | Description |
|---------|------|-------------|
| Application | 8080 | Spring Boot API |
| PostgreSQL | 5432 | Database |
| Kafka | 9092 | Message broker |
| Kafka UI | 8081 | Web UI for Kafka inspection |
| Zookeeper | 2181 | Kafka coordination |
| Grafana | 3000 | Metrics dashboards (admin/admin) |
| Prometheus | 9090 | Metrics collection |

## Monitoring

The project includes optional monitoring infrastructure:

- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **Application Metrics**: http://localhost:8080/actuator/prometheus
