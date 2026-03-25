@echo off
setlocal enabledelayedexpansion

echo 🚀 Starting Tech Test Infrastructure...

REM Start Docker Compose services
echo 📦 Starting Docker containers...
docker-compose up -d

if %errorlevel% neq 0 (
    echo ❌ Failed to start Docker containers
    exit /b 1
)

REM Wait for services to be ready
echo ⏳ Waiting for services to start...
timeout /t 10 /nobreak >nul

REM Wait for Kafka to be ready
echo 🔄 Waiting for Kafka to be ready...
set max_attempts=30
set attempt=0

:kafka_wait_loop
if !attempt! geq !max_attempts! (
    echo ❌ Kafka failed to start within expected time
    exit /b 1
)

docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --list >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ Kafka is ready!
    goto kafka_ready
)

set /a attempt+=1
echo    Attempt !attempt!/!max_attempts! - Kafka not ready yet...
timeout /t 2 /nobreak >nul
goto kafka_wait_loop

:kafka_ready

REM Wait for PostgreSQL to be ready
echo 🔄 Waiting for PostgreSQL to be ready...
set max_attempts=30
set attempt=0

:postgres_wait_loop
if !attempt! geq !max_attempts! (
    echo ❌ PostgreSQL failed to start within expected time
    exit /b 1
)

docker exec tech-test-postgres pg_isready -U postgres >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ PostgreSQL is ready!
    goto postgres_ready
)

set /a attempt+=1
echo    Attempt !attempt!/!max_attempts! - PostgreSQL not ready yet...
timeout /t 2 /nobreak >nul
goto postgres_wait_loop

:postgres_ready

REM Create Kafka topics
echo 📝 Creating Kafka topics...
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic tech-test-topic --partitions 3 --replication-factor 1
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic transactions --partitions 3 --replication-factor 1

echo 📋 Created topics:
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --list

REM Add sample transaction data
echo 🛒 Adding sample retail transaction data...

REM Sample transactions - corner shop style
set "transaction1={\"transactionId\":\"TXN-001\",\"timestamp\":\"2024-06-24T10:15:30Z\",\"customerId\":\"CUST-12345\",\"items\":[{\"name\":\"Milk\",\"price\":2.50,\"quantity\":1},{\"name\":\"Bread\",\"price\":1.20,\"quantity\":2}],\"total\":4.90,\"paymentMethod\":\"card\",\"storeId\":\"STORE-001\"}"
set "transaction2={\"transactionId\":\"TXN-002\",\"timestamp\":\"2024-06-24T10:22:15Z\",\"customerId\":\"CUST-67890\",\"items\":[{\"name\":\"Coffee\",\"price\":3.99,\"quantity\":1},{\"name\":\"Chocolate Bar\",\"price\":1.50,\"quantity\":1}],\"total\":5.49,\"paymentMethod\":\"cash\",\"storeId\":\"STORE-001\"}"
set "transaction3={\"transactionId\":\"TXN-003\",\"timestamp\":\"2024-06-24T10:35:45Z\",\"customerId\":\"CUST-11111\",\"items\":[{\"name\":\"Newspaper\",\"price\":1.00,\"quantity\":1},{\"name\":\"Energy Drink\",\"price\":2.25,\"quantity\":1}],\"total\":3.25,\"paymentMethod\":\"cash\",\"storeId\":\"STORE-001\"}"
set "transaction4={\"transactionId\":\"TXN-004\",\"timestamp\":\"2024-06-24T10:41:20Z\",\"customerId\":\"CUST-22222\",\"items\":[{\"name\":\"Apples\",\"price\":0.75,\"quantity\":3},{\"name\":\"Bananas\",\"price\":0.60,\"quantity\":2}],\"total\":3.45,\"paymentMethod\":\"card\",\"storeId\":\"STORE-001\"}"
set "transaction5={\"transactionId\":\"TXN-005\",\"timestamp\":\"2024-06-24T10:55:10Z\",\"customerId\":\"CUST-33333\",\"items\":[{\"name\":\"Cigarettes\",\"price\":12.50,\"quantity\":1},{\"name\":\"Lighter\",\"price\":1.99,\"quantity\":1}],\"total\":14.49,\"paymentMethod\":\"cash\",\"storeId\":\"STORE-001\"}"
set "transaction6={\"transactionId\":\"TXN-006\",\"timestamp\":\"2024-06-24T11:02:33Z\",\"customerId\":\"CUST-44444\",\"items\":[{\"name\":\"Sandwich\",\"price\":3.50,\"quantity\":1},{\"name\":\"Crisps\",\"price\":1.25,\"quantity\":1},{\"name\":\"Soft Drink\",\"price\":1.50,\"quantity\":1}],\"total\":6.25,\"paymentMethod\":\"card\",\"storeId\":\"STORE-001\"}"
set "transaction7={\"transactionId\":\"TXN-007\",\"timestamp\":\"2024-06-24T11:15:45Z\",\"customerId\":\"CUST-55555\",\"items\":[{\"name\":\"Ice Cream\",\"price\":2.99,\"quantity\":1}],\"total\":2.99,\"paymentMethod\":\"cash\",\"storeId\":\"STORE-001\"}"
set "transaction8={\"transactionId\":\"TXN-008\",\"timestamp\":\"2024-06-24T11:28:12Z\",\"customerId\":\"CUST-66666\",\"items\":[{\"name\":\"Toilet Paper\",\"price\":4.50,\"quantity\":1},{\"name\":\"Shampoo\",\"price\":6.99,\"quantity\":1}],\"total\":11.49,\"paymentMethod\":\"card\",\"storeId\":\"STORE-001\"}"
set "transaction9={\"transactionId\":\"TXN-009\",\"timestamp\":\"2024-06-24T11:34:55Z\",\"customerId\":\"CUST-77777\",\"items\":[{\"name\":\"Chewing Gum\",\"price\":0.99,\"quantity\":2},{\"name\":\"Mints\",\"price\":1.49,\"quantity\":1}],\"total\":3.47,\"paymentMethod\":\"cash\",\"storeId\":\"STORE-001\"}"
set "transaction10={\"transactionId\":\"TXN-010\",\"timestamp\":\"2024-06-24T11:42:18Z\",\"customerId\":\"CUST-88888\",\"items\":[{\"name\":\"Beer\",\"price\":4.99,\"quantity\":4},{\"name\":\"Nuts\",\"price\":2.25,\"quantity\":1}],\"total\":22.21,\"paymentMethod\":\"card\",\"storeId\":\"STORE-001\"}"
set "badTransaction={\"transactionId\":\"BAD-TXN\",\"timestamp\":\"INVALID-DATE\",\"customerId\":\"\",\"items\":[],\"total\":\"not-a-number\",\"paymentMethod\":\"\",\"storeId\":\"\"}"

echo 📤 Sending: TXN-001
echo !transaction1! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: TXN-002
echo !transaction2! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: TXN-003
echo !transaction3! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: TXN-004
echo !transaction4! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: TXN-005
echo !transaction5! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: TXN-006
echo !transaction6! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: TXN-007
echo !transaction7! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: TXN-008
echo !transaction8! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: TXN-009
echo !transaction9! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: TXN-010
echo !transaction10! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
timeout /t 1 /nobreak >nul

echo 📤 Sending: BAD-TXN
echo !badTransaction! | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions

echo.
echo ✅ Infrastructure started successfully!
echo.
echo 🔗 Access Points:
echo    🌐 Application:        http://localhost:8080
echo    📊 Grafana:           http://localhost:3000 (admin/admin)
echo    📈 Prometheus:        http://localhost:9090
echo    🚀 Kafka UI:          http://localhost:8081
echo    💾 Database:          localhost:5432 (postgres/postgres)
echo.
echo 🧪 Test Commands:
echo    curl http://localhost:8080/api/transactions/health
echo    curl http://localhost:8080/api/transactions/stats/STORE-001
echo    curl http://localhost:8080/api/transactions/store/STORE-001
echo    curl http://localhost:8080/actuator/prometheus
echo.
echo 📋 Kafka Topics:
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --list
echo.
echo 📦 View Transactions:
echo    docker exec tech-test-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic transactions --from-beginning --max-messages 5
echo.
echo 🚀 Ready to start the application with: gradlew.bat bootRun
echo.
pause 