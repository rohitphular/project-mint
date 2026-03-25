# Kafka Transaction Producer

This service simulates external supermarket tills sending transaction events to Kafka topics. It's designed to test how candidates handle multiple external dependencies in an event-driven architecture.

## Purpose

- Simulates external till systems publishing transaction events to Kafka
- Generates realistic transaction data at configurable rates
- Tests the candidate's ability to handle Kafka consumers alongside REST APIs
- Provides a separate external dependency to demonstrate event-driven patterns

## Configuration

### Environment Variables

- `KAFKA_BOOTSTRAP_SERVERS`: Kafka broker addresses (default: `kafka:29092`)
- `MESSAGES_PER_SECOND`: Rate of message production (default: `5`)

### Kafka Topic

- **Topic Name**: `transactions`
- **Message Format**: JSON event with transaction data
- **Partitioning**: Uses transaction ID as key for consistent partitioning

## Message Format

Each message is a JSON event with the following structure:

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
    "items": [...]
  }
}
```

## Running Locally

```bash
# Install dependencies
pip install -r requirements.txt

# Run with default settings
python kafka_producer.py

# Run with custom settings
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 MESSAGES_PER_SECOND=10 python kafka_producer.py
```

## Integration with Docker Compose

This service is integrated into the main `docker-compose.yml` and will automatically start with the rest of the infrastructure. 