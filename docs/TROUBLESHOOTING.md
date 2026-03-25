# Troubleshooting Guide

## 🔧 Common Issues & Solutions

### Docker Issues

**Docker won't start:**

```bash
# Check if Docker Desktop is running
docker --version

# Try restarting the containers
docker-compose down
docker-compose up -d

# Check if ports are available
lsof -i :8080  # Application
lsof -i :9092  # Kafka
lsof -i :5432  # PostgreSQL
lsof -i :3000  # Grafana
```

**Port conflicts:**

- Stop any existing services using these ports
- Or change ports in `docker-compose.yml` if needed

### Application Issues

**Application won't start:**

```bash
# Check if database is running
docker-compose ps

# Check database logs
docker-compose logs postgres

# Clean and restart
./gradlew clean bootRun

# Check application logs
docker-compose logs app
```

**Database connection errors:**

- Wait 30 seconds for PostgreSQL to fully start
- Check database is accessible: `docker-compose exec postgres psql -U postgres -d techtest`

### Kafka Issues

**Kafka connection issues:**

```bash
# Wait for Kafka to fully start (30 seconds)
sleep 30

# Check Kafka logs
docker-compose logs kafka

# Verify topic exists
docker-compose exec kafka kafka-topics --list --bootstrap-server localhost:9092

# Check if producer is working
docker-compose logs kafka-producer
```

**Kafka topic not found:**

- The topic is created automatically when the producer starts
- Wait for the producer to send its first message

### Issue Investigation Problems

**Can't find the reported issue:**

```bash
# Test the endpoint
curl http://localhost:8080/api/transactions/stats/STORE-001

# Check application logs for calculation details
docker-compose logs app | grep "calculation"

# Use Postman collection
# Import postman/Tech-Test-API.postman_collection.json
# Run "Get Transaction Statistics" request
```

**Look for these log messages:**

- `"Store {} statistics - Total transactions: {}, Total amount: {}, Average amount: {}"`
- `"Raw calculation: {} / {} = {}"` (trace level)

### General Issues

**Gradle issues:**

```bash
# Clean and rebuild
./gradlew clean build

# Check Java version (requires Java 17+)
java -version

# Update Gradle wrapper if needed
./gradlew wrapper --gradle-version 8.5
```

**Permission issues:**

```bash
# Make scripts executable
chmod +x gradlew
chmod +x scripts/*.sh
```

**Memory issues:**

- Increase Docker memory allocation (4GB+ recommended)
- Increase JVM heap: `./gradlew bootRun -Dspring.jvm.args="-Xmx2g"`

## 🆘 Still Stuck?

**Document your troubleshooting steps:**

1. What command did you run?
2. What error did you get?
3. What did you try to fix it?
4. What's your environment? (OS, Java version, Docker version)

**We can help during the interview!** Focus on:

- Getting the system running
- Understanding the problem
- Documenting your approach

**Remember:** We're more interested in your problem-solving process than perfect completion. 