# Till Simulator

A Python service that simulates supermarket tills by continuously calling the legacy REST API with random transaction data.

## Purpose

This service simulates the real-world scenario where multiple supermarket tills are constantly sending transaction data to the legacy REST API. It helps demonstrate:

1. **Load Testing**: Generates realistic transaction volume
2. **Data Variety**: Creates diverse transaction patterns across different stores, tills, and products
3. **Continuous Operation**: Runs 24/7 to simulate production traffic
4. **Monitoring**: Provides metrics for testing monitoring and alerting

## Features

- **Realistic Data**: Generates transactions with realistic supermarket products, prices, and categories
- **Multiple Stores**: Simulates 5 different stores (STORE-001 to STORE-005)
- **Multiple Tills**: Simulates 8 different tills (TILL-1 to TILL-8)
- **Payment Methods**: Randomly selects from card, cash, and contactless
- **Product Variety**: 20 different products across various categories (Dairy, Bakery, Meat, etc.)
- **Configurable**: Adjustable interval and API endpoint via environment variables

## Configuration

### Environment Variables

- `API_BASE_URL`: The base URL of the legacy REST API (default: `http://localhost:8080`)
- `SIMULATION_INTERVAL_SECONDS`: How often to submit transactions (default: `1`)

### Example Configuration

```bash
API_BASE_URL=http://localhost:8080
SIMULATION_INTERVAL_SECONDS=1
```

## Running

### With Docker Compose (Recommended)

The till simulator is included in the main `docker-compose.yml` and will start automatically:

```bash
docker-compose up -d
```

### Standalone

```bash
# Install dependencies
pip install -r requirements.txt

# Run the simulator
python till_simulator.py
```

## Sample Transaction Data

The simulator generates transactions like this:

```json
{
  "transactionId": "TXN-A1B2C3D4",
  "customerId": "CUST-12345",
  "storeId": "STORE-001",
  "tillId": "TILL-3",
  "paymentMethod": "card",
  "totalAmount": 15.50,
  "currency": "GBP",
  "timestamp": "2025-06-27T12:00:00.000Z",
  "items": [
    {
      "productName": "Milk",
      "productCode": "MILK001",
      "unitPrice": 2.50,
      "quantity": 1,
      "category": "Dairy"
    },
    {
      "productName": "Bread",
      "productCode": "BREAD001",
      "unitPrice": 1.20,
      "quantity": 2,
      "category": "Bakery"
    }
  ]
}
```

## Monitoring

The simulator provides logging output showing:

- Transaction submission success/failure
- Statistics every 10 transactions
- Error details for failed submissions

Example log output:
```
2025-06-27 12:00:01 - INFO - Transaction TXN-A1B2C3D4 submitted successfully - Store: STORE-001, Till: TILL-3, Amount: £15.50
2025-06-27 12:00:10 - INFO - Statistics - Success: 10, Errors: 0, Total: 10
```

## Product Catalog

The simulator includes 20 realistic supermarket products:

- **Dairy**: Milk, Eggs, Cheese, Yogurt, Butter
- **Bakery**: Bread
- **Beverages**: Coffee, Orange Juice
- **Meat**: Chicken Breast, Ham
- **Grains**: Rice, Pasta, Cereal
- **Fruit**: Bananas, Apples
- **Vegetables**: Tomatoes, Potatoes, Onions, Lettuce, Cucumber

## Use Cases

1. **Load Testing**: Test the legacy REST API under continuous load
2. **Data Generation**: Create realistic test data for development
3. **Monitoring Demo**: Show how monitoring systems handle real traffic
4. **Performance Testing**: Measure API response times under load
5. **Integration Testing**: Test the complete data flow from tills to database

## Troubleshooting

### Common Issues

1. **Connection Refused**: Make sure the Spring Boot application is running on port 8080
2. **High Error Rate**: Check if the database is running and accessible
3. **Slow Performance**: Consider increasing the interval between requests

### Logs

View the simulator logs:

```bash
docker-compose logs -f till-simulator
``` 