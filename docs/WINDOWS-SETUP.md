# Tech Test - Windows Setup Guide

This guide provides Windows-specific instructions for setting up and running the Tech Test Spring Boot application.

## 🖥️ Prerequisites for Windows

### Required Software

#### 1. Java 21

**Option A: Using Chocolatey (Recommended)**

```cmd
# Install Chocolatey if not already installed
# Run PowerShell as Administrator and execute:
Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))

# Install Java 21
choco install openjdk21
```

**Option B: Manual Installation**

1. Download OpenJDK 21 from: https://adoptium.net/
2. Install and set `JAVA_HOME` environment variable
3. Add `%JAVA_HOME%\bin` to your `PATH`

**Verify Installation:**

```cmd
java -version
```

#### 2. Docker Desktop for Windows

1. Download from: https://www.docker.com/products/docker-desktop/
2. Install Docker Desktop
3. Enable WSL 2 integration if using WSL
4. Start Docker Desktop

**Verify Installation:**

```cmd
docker --version
docker-compose --version
```

#### 3. Git for Windows

1. Download from: https://git-scm.com/download/win
2. Install with recommended settings
3. Use Git Bash or Command Prompt

### Optional Tools

- **Windows Terminal**: Enhanced command-line experience
- **PowerShell 7**: Modern PowerShell version
- **WSL 2**: Linux subsystem for better compatibility

## 🚀 Quick Start (Windows)

### 1. Clone the Repository

```cmd
git clone <repository-url>
cd tech-test
```

### 2. Start Infrastructure (Windows)

```cmd
# Using the Windows batch script
scripts\start-all.bat
```

This will:

- Start all Docker containers
- Wait for services to be ready (with Windows-compatible health checks)
- Create Kafka topics
- Populate sample transaction data
- Display access URLs

### 3. Run the Application

```cmd
# Build and run using Gradle wrapper
gradlew.bat build
gradlew.bat bootRun
```

**Alternative using PowerShell:**

```powershell
.\gradlew.bat build
.\gradlew.bat bootRun
```

## 📁 Windows File Structure

```
tech-test\
├── scripts\
│   ├── start-all.sh         # Linux/macOS script
│   └── start-all.bat        # Windows script
├── gradlew.bat              # Windows Gradle wrapper
├── gradlew                  # Linux/macOS Gradle wrapper
├── docs\
│   └── WINDOWS-SETUP.md     # This file
└── ...
```

## 🧪 Testing Endpoints (Windows)

### Using curl (if installed)

```cmd
curl http://localhost:8080/api/health
curl http://localhost:8080/api/transactions/stats/STORE-001
curl -X POST http://localhost:8080/api/transactions/sample
```

### Using PowerShell (Invoke-RestMethod)

```powershell
# Test health endpoint
Invoke-RestMethod -Uri "http://localhost:8080/api/health" -Method GET

# Test statistics endpoint
Invoke-RestMethod -Uri "http://localhost:8080/api/transactions/stats/STORE-001" -Method GET

# Create sample transaction
Invoke-RestMethod -Uri "http://localhost:8080/api/transactions/sample" -Method POST

# Submit custom transaction
$transactionData = @{
    transactionId = "TXN-WINDOWS-001"
    customerId = "CUST-WINDOWS"
    storeId = "STORE-001"
    tillId = "TILL-001"
    paymentMethod = "card"
    totalAmount = 6.49
    currency = "GBP"
    timestamp = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ssZ")
    items = @(
        @{ 
            productName = "Coffee"
            productCode = "COFFEE001"
            unitPrice = 3.99
            quantity = 1
            category = "Beverages"
        }
        @{ 
            productName = "Muffin"
            productCode = "MUFFIN001"
            unitPrice = 2.50
            quantity = 1
            category = "Bakery"
        }
    )
} | ConvertTo-Json -Depth 4

Invoke-RestMethod -Uri "http://localhost:8080/api/transactions/submit" -Method POST -Body $transactionData -ContentType "application/json"
```

### Using Windows Command Prompt (without curl)

Install curl for Windows or use PowerShell commands above.

**Install curl via Chocolatey:**

```cmd
choco install curl
```

## 🔧 Windows-Specific Configuration

### Environment Variables

Set these if needed (usually auto-detected):

```cmd
# Set JAVA_HOME (adjust path as needed)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot

# Add Java to PATH
set PATH=%JAVA_HOME%\bin;%PATH%

# Optional: Set Docker host (usually not needed)
set DOCKER_HOST=tcp://localhost:2375
```

### Making them permanent:

```cmd
# Using Command Prompt (requires admin)
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot" /M
setx PATH "%PATH%;%JAVA_HOME%\bin" /M
```

### Docker Desktop Settings

1. **Resources → Advanced**:
    - Memory: 4GB minimum
    - CPU: 2+ cores
    - Disk: 20GB+

2. **Resources → File Sharing**:
    - Add your project directory if needed

3. **General Settings**:
    - ✅ Use Docker Compose V2
    - ✅ Start Docker Desktop when you log in

## 📊 Accessing Services (Windows)

All services run on the same ports as Linux/macOS:

| Service         | URL                   | Credentials       |
|-----------------|-----------------------|-------------------|
| **Application** | http://localhost:8080 | -                 |
| **Grafana**     | http://localhost:3000 | admin/admin       |
| **Prometheus**  | http://localhost:9090 | -                 |
| **Kafka UI**    | http://localhost:8081 | -                 |
| **PostgreSQL**  | localhost:5432        | postgres/postgres |

**Windows-specific access methods:**

- Use Windows browser for web UIs
- Use Windows network stack for database connections
- Docker Desktop provides easy container management

## 🚨 Windows Troubleshooting

### Common Issues

#### 1. Port Already in Use

```cmd
# Find process using port 8080
netstat -ano | findstr :8080

# Kill process by PID (replace XXXX with actual PID)
taskkill /PID XXXX /F
```

#### 2. Docker Issues

- **Docker not starting**: Restart Docker Desktop
- **Permission denied**: Run Command Prompt as Administrator
- **WSL 2 issues**: Update WSL and restart

#### 3. Java Issues

```cmd
# Check Java installation
java -version
where java

# Check JAVA_HOME
echo %JAVA_HOME%
```

#### 4. Line Ending Issues

If you encounter script execution issues:

```cmd
# Convert line endings using Git
git config --global core.autocrlf true
git checkout .
```

#### 5. Gradle Issues

```cmd
# Clean and rebuild
gradlew.bat clean build

# Force download dependencies
gradlew.bat build --refresh-dependencies
```

### Windows-Specific Commands

#### View Docker Containers

```cmd
docker ps
docker-compose ps
```

#### View Application Logs

```cmd
# In the same terminal where you ran gradlew bootRun
# Logs will appear automatically

# Or check Docker logs
docker-compose logs -f postgres
docker-compose logs -f kafka
```

#### Stop Services

```cmd
# Stop Spring Boot app: Ctrl+C in the terminal

# Stop Docker containers
docker-compose down

# Force stop everything
docker-compose down --volumes
```

## 📱 Windows Development Tools

### Recommended IDEs

- **IntelliJ IDEA**: Excellent Spring Boot support
- **Visual Studio Code**: Lightweight with Java extensions
- **Eclipse**: Traditional Java IDE

### Useful Windows Tools

- **Windows Terminal**: Better command-line experience
- **PowerToys**: Windows productivity utilities
- **Postman**: API testing (cross-platform)
- **Docker Desktop**: Container management GUI

### PowerShell Profile Setup

Add to your PowerShell profile for convenience:

```powershell
# Edit profile
notepad $PROFILE

# Add these functions:
function Start-TechTest {
    Set-Location "C:\path\to\tech-test"
    .\scripts\start-all.bat
}

function Run-TechTest {
    .\gradlew.bat bootRun
}

function Test-TechTest {
    Invoke-RestMethod -Uri "http://localhost:8080/api/health"
}
```

## 🔄 Windows Update Script

Create `scripts\update-all.bat` for easy updates:

```batch
@echo off
echo 🔄 Updating Tech Test application...

git pull origin main
docker-compose pull
gradlew.bat clean build

echo ✅ Update complete!
pause
```

## 🌐 Browser Recommendations

**For optimal dashboard viewing:**

- **Chrome/Edge**: Best Grafana compatibility
- **Firefox**: Good alternative
- **Enable JavaScript**: Required for all dashboards

**Bookmark these URLs:**

- http://localhost:8080/api/transactions/health
- http://localhost:8080/api/transactions/stats/STORE-001
- http://localhost:3000 (Grafana)
- http://localhost:8081 (Kafka UI)
- http://localhost:9090 (Prometheus)

## 📞 Windows Support

### Getting Help

1. Check Docker Desktop dashboard for container status
2. Review application logs in Command Prompt/PowerShell
3. Use Windows Event Viewer for system-level issues
4. Consult Windows-specific Java documentation

### Performance Tips

- **SSD recommended**: For Docker volume performance
- **8GB+ RAM**: For comfortable development
- **Windows 10/11**: Better Docker integration
- **WSL 2**: Consider for better Linux compatibility

### Antivirus Considerations

Add exclusions for:

- Docker installation directory
- Project directory
- Gradle cache: `%USERPROFILE%\.gradle`

This Windows setup ensures a smooth development experience regardless of your operating system! 🚀 