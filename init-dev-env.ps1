# PolyHermes Development Environment Initialization Script
# Usage: .\init-dev-env.ps1

param(
    [switch]$SkipDbCreate
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = $ScriptDir
$EnvFile = Join-Path $ProjectRoot ".env"
$EnvExampleFile = Join-Path $ProjectRoot ".env.example"
$BackendAppProps = Join-Path $ProjectRoot "backend\src\main\resources\application.properties"
$FrontendEnvFile = Join-Path $ProjectRoot "frontend\.env"

function Write-Step {
    param([string]$Message)
    Write-Host "`n[STEP] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Err {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Yellow
}

function Get-EnvValue {
    param([string]$Key, [string]$Default = "")
    $value = $envHash[$Key]
    if ([string]::IsNullOrEmpty($value)) {
        return $Default
    }
    return $value
}

function Update-AppProperty {
    param(
        [string]$File,
        [string]$Key,
        [string]$Value
    )
    $content = Get-Content $File -Raw
    if ($content -match "($Key\s*=\s*)(.*)") {
        $newContent = $content -replace "($Key\s*=\s*).*", "`$1$Value"
        Set-Content -Path $File -Value $newContent -NoNewline
    } else {
        Add-Content -Path $File -Value "`n$Key=$Value"
    }
}

Write-Host "========================================" -ForegroundColor Magenta
Write-Host "  PolyHermes Dev Environment Init" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta

Write-Step "1. Checking .env file"
if (-not (Test-Path $EnvFile)) {
    if (Test-Path $EnvExampleFile) {
        Copy-Item $EnvExampleFile $EnvFile
        Write-Info ".env file created from .env.example"
        Write-Info "Please edit .env file and fill in your database password"
        Write-Host ""
        Write-Host "File: $EnvFile" -ForegroundColor White
        exit 1
    } else {
        Write-Err ".env.example not found"
        exit 1
    }
}

$envHash = @{}
Get-Content $EnvFile | ForEach-Object {
    if ($_ -match "^\s*(.+?)\s*=\s*(.*)$") {
        $envHash[$Matches[1]] = $Matches[2]
    }
}

$DB_HOST = Get-EnvValue "DB_HOST" "localhost:3306"
$DB_USERNAME = Get-EnvValue "DB_USERNAME" "root"
$DB_PASSWORD = Get-EnvValue "DB_PASSWORD"
$DB_NAME = Get-EnvValue "DB_NAME" "polyhermes"
$SERVER_PORT = Get-EnvValue "SERVER_PORT" "8000"
$JWT_SECRET = Get-EnvValue "JWT_SECRET" "change-me-in-production"
$VITE_API_URL = Get-EnvValue "VITE_API_URL" "http://localhost:8000"
$VITE_WS_URL = Get-EnvValue "VITE_WS_URL" "ws://localhost:8000"
$VITE_ENABLE_SYSTEM_UPDATE = Get-EnvValue "VITE_ENABLE_SYSTEM_UPDATE" "false"

if ([string]::IsNullOrEmpty($DB_PASSWORD) -or $DB_PASSWORD -eq "your_password_here") {
    Write-Err "DB_PASSWORD is not set in .env file"
    Write-Host "Please edit $EnvFile and set a valid database password" -ForegroundColor Yellow
    exit 1
}

Write-Success ".env file loaded"

Write-Step "2. Checking prerequisites"

Write-Host "  - Checking MySQL..." -NoNewline
try {
    $mysqlCmd = Get-Command mysql -ErrorAction SilentlyContinue
    if (-not $mysqlCmd) {
        Write-Err "MySQL client not found"
        Write-Host "Please install MySQL or add it to PATH" -ForegroundColor Yellow
        exit 1
    }
    $null = & mysql --version 2>&1
    Write-Success "MySQL found"
} catch {
    Write-Err "MySQL not found"
    exit 1
}

Write-Host "  - Checking JDK..." -NoNewline
try {
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        Write-Err "JDK not found"
        Write-Host "Please install JDK 17+ or add it to PATH" -ForegroundColor Yellow
        exit 1
    }
    $javaVersion = & java -version 2>&1
    if ($javaVersion -match "version\s+`"(\d+)\.(\d+)") {
        $major = [int]$Matches[1]
        $minor = [int]$Matches[2]
        if ($major -lt 17) {
            Write-Err "JDK version is $major.$minor, but JDK 17+ is required"
            exit 1
        }
    }
    Write-Success "JDK 17+ found"
} catch {
    Write-Err "Failed to check JDK"
    exit 1
}

Write-Host "  - Checking Node.js..." -NoNewline
try {
    $nodeCmd = Get-Command node -ErrorAction SilentlyContinue
    if (-not $nodeCmd) {
        Write-Info "Node.js not found (optional for backend-only development)"
    } else {
        $nodeVersion = & node --version 2>&1
        Write-Success "Node.js found ($nodeVersion)"
    }
} catch {
    Write-Info "Node.js not found (optional for backend-only development)"
}

if (-not $SkipDbCreate) {
    Write-Step "3. Testing database connection"
    $mysqlConnStr = "mysql://$DB_HOST"
    $null = & mysql -h $DB_HOST.Split(":")[0] -P $DB_HOST.Split(":")[1] -u $DB_USERNAME -p$DB_PASSWORD -e "SELECT 1" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Cannot connect to MySQL at $DB_HOST"
        Write-Host "Please check your database credentials in .env" -ForegroundColor Yellow
        exit 1
    }
    Write-Success "Database connection OK"

    Write-Step "4. Creating database"
    $null = & mysql -h $DB_HOST.Split(":")[0] -P $DB_HOST.Split(":")[1] -u $DB_USERNAME -p$DB_PASSWORD -e "CREATE DATABASE IF NOT EXISTS ``$DB_NAME`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Failed to create database"
        exit 1
    }
    Write-Success "Database '$DB_NAME' ready"
} else {
    Write-Info "Skipping database creation"
}

Write-Step "5. Updating backend configuration"
if (Test-Path $BackendAppProps) {
    $dbUrl = "jdbc:mysql://${DB_HOST}/${DB_NAME}?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true"
    Update-AppProperty -File $BackendAppProps -Key "spring.datasource.url" -Value "`${DB_URL:$dbUrl}"
    Update-AppProperty -File $BackendAppProps -Key "spring.datasource.username" -Value "`${DB_USERNAME:$DB_USERNAME}"
    Update-AppProperty -File $BackendAppProps -Key "spring.datasource.password" -Value "`${DB_PASSWORD:$DB_PASSWORD}"
    Update-AppProperty -File $BackendAppProps -Key "server.port" -Value "`${SERVER_PORT:$SERVER_PORT}"
    Update-AppProperty -File $BackendAppProps -Key "jwt.secret" -Value "`${JWT_SECRET:$JWT_SECRET}"
    Write-Success "Backend configuration updated"
} else {
    Write-Err "application.properties not found at $BackendAppProps"
}

Write-Step "6. Creating frontend environment file"
$frontendEnvContent = @"
VITE_API_URL=$VITE_API_URL
VITE_WS_URL=$VITE_WS_URL
VITE_ENABLE_SYSTEM_UPDATE=$VITE_ENABLE_SYSTEM_UPDATE
"@
Set-Content -Path $FrontendEnvFile -Value $frontendEnvContent -NoNewline
Write-Success "Frontend environment file created"

Write-Host ""
Write-Host "========================================" -ForegroundColor Magenta
Write-Host "  Initialization Complete!" -ForegroundColor Magenta
Write-Host "========================================" -ForegroundColor Magenta
Write-Host ""
Write-Host "Next steps:" -ForegroundColor White
Write-Host "  1. Start MySQL (if not running)" -ForegroundColor White
Write-Host "  2. Backend: cd backend; ./gradlew bootRun" -ForegroundColor White
Write-Host "  3. Frontend: cd frontend; npm install && npm run dev" -ForegroundColor White
Write-Host ""
