# Vega Tech Test

## Purpose

This repository contains the codebase for your **live coding session** with the Vega team.

**Your task before the interview:**
1. Set up and run the project on your machine
2. Familiarise yourself with the code and architecture
3. Be ready to work on this code during the live session

**You don't need to modify anything.** Just make sure everything runs and you understand how it's structured.

**Please bring your laptop with you**. We want to see how you work, and we think you can do that best on a machine with the hardware and software you're familiar with. If you are unable to bring a suitable laptop with you, please **let us know ahead of time** so that we can set up a machine for you.

## Prerequisites

- **Java 21** or higher
- **Docker** and **Docker Compose**
- **Git**

## Project Setup

### 1. Start the Infrastructure

```bash
# Option A: Automated script (recommended)
./scripts/start-all.sh

# Option B: Manual
docker-compose up -d
```

This starts: PostgreSQL, Kafka, Zookeeper, and auxiliary services.

### 2. Run the Application

```bash
# Linux/macOS
./gradlew bootRun

# Windows
gradlew.bat bootRun
```

### 3. Verify Everything Works

```bash
# Health check
curl http://localhost:8080/api/transactions/health

# View store statistics
curl http://localhost:8080/api/transactions/stats/STORE-001

# Submit a test transaction
curl -X POST http://localhost:8080/api/transactions/submit \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"TEST-001","storeId":"STORE-001","tillId":"TILL-1","customerId":"CUST-001","totalAmount":25.50,"paymentMethod":"card","items":[{"productName": "Cheese", "productCode": "CHEESE001", "unitPrice": 4.5, "quantity": 2, "category": "Dairy"}]}'
```

If all these commands respond successfully, you're ready.

## Additional Documentation

- **[Preparation Guide](docs/PREPARATION-GUIDE.md)** - What to read and understand before the interview
- **[Technical Details](docs/TECHNICAL.md)** - Architecture, message formats, APIs
- **[Troubleshooting](docs/TROUBLESHOOTING.md)** - Common issues and solutions
- **[Windows Setup](docs/WINDOWS-SETUP.md)** - Windows-specific instructions

## FAQ

**Do I need to implement anything before the interview?**
No. Just make sure the project compiles and runs correctly.

**What if I can't get it working?**
Document what you tried and what errors you encountered. We can help you at the start of the session.

**Do I need to know Kafka in depth?**
Not required. The preparation guide covers the relevant concepts.

---

**Having issues?** Check the [troubleshooting guide](docs/TROUBLESHOOTING.md) or contact us.
