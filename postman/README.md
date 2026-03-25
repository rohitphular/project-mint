# Tech Test API - Postman Collection

This directory contains a comprehensive Postman collection and environment for testing the Tech Test Spring Boot API.

## 📁 Files

- `Tech-Test-API.postman_collection.json` - Complete API collection with all endpoints
- `Tech-Test-Environment.postman_environment.json` - Environment variables for different deployment scenarios
- `README.md` - This documentation file

## 🚀 Quick Setup

### 1. Install Postman

Download and install Postman from: https://www.postman.com/downloads/

### 2. Import Collection

1. Open Postman
2. Click **Import** in the top left
3. Drag and drop `Tech-Test-API.postman_collection.json` OR click **Upload Files** and select it
4. Click **Import**

### 3. Import Environment

1. Click **Import** again
2. Drag and drop `Tech-Test-Environment.postman_environment.json` OR click **Upload Files** and select it
3. Click **Import**

### 4. Select Environment

1. In the top right corner, click the environment dropdown
2. Select **Tech Test Environment**
3. Verify the `base_url` is set to `http://localhost:8080`

## 📋 Collection Structure

### 🏠 Core Application
- **Application Health** - `GET /api/health` - Basic health check
- **Transaction Statistics** - `GET /api/transactions/stats/{storeId}` - Get store statistics

### 🛒 Transaction Processing
- **Generate Sample Transaction** - `POST /api/transactions/sample` - Create random transaction
- **Submit Custom Transaction** - `POST /api/transactions/submit` - Submit custom transaction
- **Large Transaction** - `POST /api/transactions/submit` - Test with larger purchase
- **Transaction Service Health** - `GET /api/transactions/health` - Service health check

### 📊 Monitoring & Observability
- **Prometheus Metrics** - `GET /actuator/prometheus` - All metrics in Prometheus format
- **Actuator Health** - `GET /actuator/health` - Detailed health status
- **Actuator Info** - `GET /actuator/info` - Application information
- **Actuator Metrics** - `GET /actuator/metrics` - List all available metrics

## 🧪 Testing Workflow

### Before Starting
1. Ensure infrastructure is running: `./scripts/start-all.sh`
2. Start the Spring Boot application: `./gradlew bootRun`

### Basic Flow Test
1. **Health Check** - Run `Application Health` to verify app is running
2. **Statistics Test** - Run `Transaction Statistics` to verify statistics endpoint
3. **Transaction Test** - Run `Generate Sample Transaction` to test full pipeline

### Load Testing
1. Use the **Runner** feature in Postman
2. Select the **Transaction Processing** folder
3. Set iterations (e.g., 10-50)
4. Monitor metrics in Grafana during the run

### Error Testing
The collection includes tests for error scenarios:
- Empty message bodies
- Missing required fields
- Large payloads

## 🔧 Environment Variables

| Variable | Default Value | Description |
|----------|--------------|-------------|
| `base_url` | `http://localhost:8080` | Application base URL |
| `kafka_ui_url` | `http://localhost:8081` | Kafka UI for message inspection |
| `grafana_url` | `http://localhost:3000` | Grafana dashboard |
| `prometheus_url` | `http://localhost:9090` | Prometheus metrics |
| `postgres_host` | `localhost:5432` | PostgreSQL connection |
| `kafka_host` | `localhost:9092` | Kafka broker |

### For Different Environments

#### Production Environment
Create a new environment with:
```json
{
  "base_url": "https://your-production-domain.com",
  "kafka_ui_url": "https://kafka-ui.your-domain.com",
  "grafana_url": "https://grafana.your-domain.com"
}
```

#### Docker Environment
If running the app in Docker:
```json
{
  "base_url": "http://localhost:8080",
  "postgres_host": "localhost:5432",
  "kafka_host": "localhost:9092"
}
```

## 📈 Monitoring Integration

### View Real-time Metrics
1. Run API requests in Postman
2. Open Grafana: `{{grafana_url}}` (admin/admin)
3. Navigate to "Tech Test Spring Boot Dashboard"
4. Watch metrics update in real-time

### Kafka Message Inspection
1. Run transaction endpoints
2. Open Kafka UI: `{{kafka_ui_url}}`
3. Browse topics → `transactions`
4. View messages and their processing status

### Custom Metrics Testing
The collection includes endpoints that generate specific custom metrics:
- `transaction_submissions_total` - Transaction submissions
- `transaction_retrievals_total` - Transaction retrievals
- `transaction_submission_duration` - Processing time

## 🔍 Pre-request Scripts

The collection includes automated scripts that:
- Set default base URL if not configured
- Add timestamps to requests
- Validate environment setup

## ✅ Test Scripts

Each request includes automated tests that verify:
- Response time < 2000ms
- Status code is 200
- Response format is correct
- Required fields are present

## 🚨 Troubleshooting

### Common Issues

1. **Connection Refused**
   - Ensure Spring Boot app is running: `./gradlew bootRun`
   - Check if infrastructure is up: `docker-compose ps`

2. **Database Errors**
   - Verify PostgreSQL is running: `docker-compose logs postgres`
   - Check database migrations: Look for Liquibase logs

3. **Kafka Errors**
   - Check Kafka status: `docker-compose logs kafka`
   - Verify topics exist: Use Kafka UI at `localhost:8081`

4. **404 Errors**
   - Verify base URL in environment variables
   - Check application logs for startup errors

### Debug Steps
1. Run `Application Health` first
2. Check `Actuator Health` for detailed status
3. Monitor application logs
4. Use Grafana dashboards for system metrics

## 📝 Example Requests

### Submit Custom Transaction
```json
{
    "transactionId": "TXN-POSTMAN-001",
    "customerId": "CUST-POSTMAN",
    "storeId": "STORE-001",
    "tillId": "TILL-001",
    "paymentMethod": "card",
    "totalAmount": 8.49,
    "currency": "GBP",
    "timestamp": "2024-01-15T10:00:00Z",
    "items": [
        {
            "productName": "Coffee",
            "productCode": "COFFEE001",
            "unitPrice": 3.99,
            "quantity": 1,
            "category": "Beverages"
        },
        {
            "productName": "Sandwich",
            "productCode": "SANDWICH001",
            "unitPrice": 4.50,
            "quantity": 1,
            "category": "Food"
        }
    ]
}
```

### Send Kafka Message
```json
{
    "message": "Test message from Postman",
    "key": "postman-test-key"
}
```

This collection provides comprehensive testing capabilities for all aspects of the Tech Test application! 🎯 