# Tech Test Infrastructure Startup Script (PowerShell)
# This script starts all required infrastructure and populates Kafka with sample data

param(
    [switch]$SkipHealthChecks = $false,
    [int]$MaxAttempts = 30
)

Write-Host "🚀 Starting Tech Test Infrastructure..." -ForegroundColor Green

# Start Docker Compose services
Write-Host "📦 Starting Docker containers..." -ForegroundColor Yellow
try {
    docker-compose up -d
    if ($LASTEXITCODE -ne 0) {
        throw "Docker compose failed"
    }
} catch {
    Write-Host "❌ Failed to start Docker containers" -ForegroundColor Red
    exit 1
}

if (-not $SkipHealthChecks) {
    # Wait for services to be ready
    Write-Host "⏳ Waiting for services to start..." -ForegroundColor Yellow
    Start-Sleep -Seconds 10

    # Wait for Kafka to be ready
    Write-Host "🔄 Waiting for Kafka to be ready..." -ForegroundColor Yellow
    $attempt = 0
    do {
        $attempt++
        $kafkaReady = $false
        try {
            docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --list | Out-Null
            $kafkaReady = $true
            Write-Host "✅ Kafka is ready!" -ForegroundColor Green
        } catch {
            Write-Host "   Attempt $attempt/$MaxAttempts - Kafka not ready yet..." -ForegroundColor Gray
            Start-Sleep -Seconds 2
        }
    } while (-not $kafkaReady -and $attempt -lt $MaxAttempts)

    if (-not $kafkaReady) {
        Write-Host "❌ Kafka failed to start within expected time" -ForegroundColor Red
        exit 1
    }

    # Wait for PostgreSQL to be ready
    Write-Host "🔄 Waiting for PostgreSQL to be ready..." -ForegroundColor Yellow
    $attempt = 0
    do {
        $attempt++
        $postgresReady = $false
        try {
            docker exec tech-test-postgres pg_isready -U postgres | Out-Null
            $postgresReady = $true
            Write-Host "✅ PostgreSQL is ready!" -ForegroundColor Green
        } catch {
            Write-Host "   Attempt $attempt/$MaxAttempts - PostgreSQL not ready yet..." -ForegroundColor Gray
            Start-Sleep -Seconds 2
        }
    } while (-not $postgresReady -and $attempt -lt $MaxAttempts)

    if (-not $postgresReady) {
        Write-Host "❌ PostgreSQL failed to start within expected time" -ForegroundColor Red
        exit 1
    }
}

# Create Kafka topics
Write-Host "📝 Creating Kafka topics..." -ForegroundColor Yellow
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic tech-test-topic --partitions 3 --replication-factor 1
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic transactions --partitions 3 --replication-factor 1

Write-Host "📋 Created topics:" -ForegroundColor Cyan
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --list

# Add sample transaction data
Write-Host "🛒 Adding sample retail transaction data..." -ForegroundColor Yellow

# Sample transactions array
$transactions = @(
    '{"transactionId":"TXN-001","timestamp":"2024-06-24T10:15:30Z","customerId":"CUST-12345","items":[{"name":"Milk","price":2.50,"quantity":1},{"name":"Bread","price":1.20,"quantity":2}],"total":4.90,"paymentMethod":"card","storeId":"STORE-001"}',
    '{"transactionId":"TXN-002","timestamp":"2024-06-24T10:22:15Z","customerId":"CUST-67890","items":[{"name":"Coffee","price":3.99,"quantity":1},{"name":"Chocolate Bar","price":1.50,"quantity":1}],"total":5.49,"paymentMethod":"cash","storeId":"STORE-001"}',
    '{"transactionId":"TXN-003","timestamp":"2024-06-24T10:35:45Z","customerId":"CUST-11111","items":[{"name":"Newspaper","price":1.00,"quantity":1},{"name":"Energy Drink","price":2.25,"quantity":1}],"total":3.25,"paymentMethod":"cash","storeId":"STORE-001"}',
    '{"transactionId":"TXN-004","timestamp":"2024-06-24T10:41:20Z","customerId":"CUST-22222","items":[{"name":"Apples","price":0.75,"quantity":3},{"name":"Bananas","price":0.60,"quantity":2}],"total":3.45,"paymentMethod":"card","storeId":"STORE-001"}',
    '{"transactionId":"TXN-005","timestamp":"2024-06-24T10:55:10Z","customerId":"CUST-33333","items":[{"name":"Cigarettes","price":12.50,"quantity":1},{"name":"Lighter","price":1.99,"quantity":1}],"total":14.49,"paymentMethod":"cash","storeId":"STORE-001"}',
    '{"transactionId":"TXN-006","timestamp":"2024-06-24T11:02:33Z","customerId":"CUST-44444","items":[{"name":"Sandwich","price":3.50,"quantity":1},{"name":"Crisps","price":1.25,"quantity":1},{"name":"Soft Drink","price":1.50,"quantity":1}],"total":6.25,"paymentMethod":"card","storeId":"STORE-001"}',
    '{"transactionId":"TXN-007","timestamp":"2024-06-24T11:15:45Z","customerId":"CUST-55555","items":[{"name":"Ice Cream","price":2.99,"quantity":1}],"total":2.99,"paymentMethod":"cash","storeId":"STORE-001"}',
    '{"transactionId":"TXN-008","timestamp":"2024-06-24T11:28:12Z","customerId":"CUST-66666","items":[{"name":"Toilet Paper","price":4.50,"quantity":1},{"name":"Shampoo","price":6.99,"quantity":1}],"total":11.49,"paymentMethod":"card","storeId":"STORE-001"}',
    '{"transactionId":"TXN-009","timestamp":"2024-06-24T11:34:55Z","customerId":"CUST-77777","items":[{"name":"Chewing Gum","price":0.99,"quantity":2},{"name":"Mints","price":1.49,"quantity":1}],"total":3.47,"paymentMethod":"cash","storeId":"STORE-001"}',
    '{"transactionId":"TXN-010","timestamp":"2024-06-24T11:42:18Z","customerId":"CUST-88888","items":[{"name":"Beer","price":4.99,"quantity":4},{"name":"Nuts","price":2.25,"quantity":1}],"total":22.21,"paymentMethod":"card","storeId":"STORE-001"}',
    '{"transactionId":"BAD-TXN","timestamp":"INVALID-DATE","customerId":"","items":[],"total":"not-a-number","paymentMethod":"","storeId":""}'
)

foreach ($transaction in $transactions) {
    # Extract transaction ID for logging
    $transactionObj = $transaction | ConvertFrom-Json
    $transactionId = $transactionObj.transactionId
    
    Write-Host "📤 Sending: $transactionId" -ForegroundColor Cyan
    
    # Send transaction to Kafka
    $transaction | docker exec -i tech-test-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic transactions
    
    Start-Sleep -Milliseconds 500
}

Write-Host ""
Write-Host "✅ Infrastructure started successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "🔗 Access Points:" -ForegroundColor Cyan
Write-Host "   🌐 Application:        http://localhost:8080" -ForegroundColor White
Write-Host "   📊 Grafana:           http://localhost:3000 (admin/admin)" -ForegroundColor White
Write-Host "   📈 Prometheus:        http://localhost:9090" -ForegroundColor White
Write-Host "   🚀 Kafka UI:          http://localhost:8081" -ForegroundColor White
Write-Host "   💾 Database:          localhost:5432 (postgres/postgres)" -ForegroundColor White
Write-Host ""
Write-Host "🧪 Test Commands:" -ForegroundColor Cyan
Write-Host "   Invoke-RestMethod -Uri 'http://localhost:8080/api/transactions/health'" -ForegroundColor White
Write-Host "   Invoke-RestMethod -Uri 'http://localhost:8080/api/transactions/stats/STORE-001'" -ForegroundColor White
Write-Host "   Invoke-RestMethod -Uri 'http://localhost:8080/api/transactions/store/STORE-001'" -ForegroundColor White
Write-Host "   Invoke-RestMethod -Uri 'http://localhost:8080/actuator/prometheus'" -ForegroundColor White
Write-Host ""
Write-Host "📋 Kafka Topics:" -ForegroundColor Cyan
docker exec tech-test-kafka kafka-topics --bootstrap-server localhost:9092 --list
Write-Host ""
Write-Host "📦 View Transactions:" -ForegroundColor Cyan
Write-Host "   docker exec tech-test-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic transactions --from-beginning --max-messages 5" -ForegroundColor White
Write-Host ""
Write-Host "🚀 Ready to start the application with: .\gradlew.bat bootRun" -ForegroundColor Green
Write-Host ""

# Optional: Open URLs in default browser
$openBrowser = Read-Host "Open dashboards in browser? (y/N)"
if ($openBrowser -eq 'y' -or $openBrowser -eq 'Y') {
    Start-Process "http://localhost:3000"  # Grafana
    Start-Process "http://localhost:8081"  # Kafka UI
    Start-Process "http://localhost:9090"  # Prometheus
} 