param(
    [switch]$InstallDeps
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "backend"
$FrontendDir = Join-Path $ProjectRoot "frontend"
$RunDir = Join-Path $ProjectRoot ".run"
$BackendLog = Join-Path $RunDir "backend.log"
$FrontendLog = Join-Path $RunDir "frontend.log"
$BackendPidFile = Join-Path $RunDir "backend.pid"
$FrontendPidFile = Join-Path $RunDir "frontend.pid"
$EnvFile = Join-Path $ProjectRoot ".env"
$FrontendPort = 3000

$envHash = @{}

function Write-Step {
    param([string]$Message)
    Write-Host "[STEP] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Load-EnvFile {
    if (-not (Test-Path $EnvFile)) {
        return
    }

    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match "^\s*(.+?)\s*=\s*(.*)$") {
            $envHash[$Matches[1]] = $Matches[2]
        }
    }
}

function Get-EnvValue {
    param(
        [string]$Key,
        [string]$Default = ""
    )

    $value = $envHash[$Key]
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $Default
    }

    return $value
}

function Get-ListeningPid {
    param([int]$Port)

    $connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $connection) {
        return $null
    }

    return [int]$connection.OwningProcess
}

function Wait-ListeningPid {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 90,
        [int]$ExcludePid = 0
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
        $listeningPids = @($connections | Where-Object { $_ -and $_ -ne $ExcludePid })
        if ($listeningPids.Count -gt 0) {
            return [int]$listeningPids[0]
        }

        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    return $null
}

function Assert-Command {
    param(
        [string]$Name,
        [string]$Hint
    )

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name 未安装。$Hint"
    }
}

function Test-RunningPid {
    param([string]$PidFile)

    if (-not (Test-Path $PidFile)) {
        return $false
    }

    $pidValue = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($pidValue)) {
        return $false
    }

    $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    return $null -ne $process
}

function Start-BackgroundCommand {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [string]$Command,
        [string]$LogFile,
        [string]$PidFile,
        [int]$Port
    )

    $listeningPid = Get-ListeningPid -Port $Port
    if ($null -ne $listeningPid) {
        throw "$Name 端口 $Port 已被 PID=$listeningPid 占用。请先停止现有进程。"
    }

    if (Test-Path $LogFile) {
        Remove-Item $LogFile -Force -ErrorAction SilentlyContinue
    }

    $errorLogFile = "$LogFile.err"
    if (Test-Path $errorLogFile) {
        Remove-Item $errorLogFile -Force -ErrorAction SilentlyContinue
    }

    $process = Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c", $Command `
        -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput $LogFile `
        -RedirectStandardError $errorLogFile `
        -PassThru

    $servicePid = Wait-ListeningPid -Port $Port -ExcludePid $process.Id
    if ($null -eq $servicePid) {
        try {
            if (-not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {
            Write-Warn "$Name 启动超时，已终止包装进程。"
        }

        throw "$Name 启动失败，端口 $Port 在规定时间内未监听。请检查日志：$LogFile"
    }

    Set-Content -Path $PidFile -Value $servicePid -NoNewline
    Write-Ok "$Name 已启动，PID=$servicePid，Port=$Port"
}

Write-Step "检查依赖"
Assert-Command -Name "java" -Hint "请安装 JDK 17+。"
Assert-Command -Name "node" -Hint "请安装 Node.js 18+。"
Assert-Command -Name "npm" -Hint "请安装 npm。"

if (-not (Test-Path (Join-Path $BackendDir "gradlew.bat"))) {
    throw "未找到 backend/gradlew.bat"
}

if (-not (Test-Path (Join-Path $FrontendDir "package.json"))) {
    throw "未找到 frontend/package.json"
}

if (-not (Test-Path $RunDir)) {
    New-Item -ItemType Directory -Path $RunDir | Out-Null
}

Load-EnvFile
$BackendPort = [int](Get-EnvValue -Key "SERVER_PORT" -Default "8000")

if ($InstallDeps -or -not (Test-Path (Join-Path $FrontendDir "node_modules"))) {
    Write-Step "安装前端依赖"
    & npm install --prefix $FrontendDir
    if ($LASTEXITCODE -ne 0) {
        throw "前端依赖安装失败"
    }
}

if (-not (Test-Path (Join-Path $ProjectRoot ".env"))) {
    Write-Warn "根目录 .env 不存在。建议先执行 .\dev-scripts\init-dev-env.ps1"
}

if (-not (Test-Path (Join-Path $FrontendDir ".env"))) {
    Write-Warn "frontend/.env 不存在。建议先执行 .\dev-scripts\init-dev-env.ps1"
}

Write-Step "启动后端"
Start-BackgroundCommand `
    -Name "Backend" `
    -WorkingDirectory $BackendDir `
    -Command "gradlew.bat bootRun" `
    -LogFile $BackendLog `
    -PidFile $BackendPidFile `
    -Port $BackendPort

Start-Sleep -Seconds 2

Write-Step "启动前端"
Start-BackgroundCommand `
    -Name "Frontend" `
    -WorkingDirectory $FrontendDir `
    -Command "npm run dev" `
    -LogFile $FrontendLog `
    -PidFile $FrontendPidFile `
    -Port $FrontendPort

Write-Host ""
Write-Ok "前后端已启动"
Write-Host "Frontend: http://localhost:3000"
Write-Host "Backend:  http://localhost:$BackendPort"
Write-Host "Backend Log:  $BackendLog"
Write-Host "Frontend Log: $FrontendLog"
Write-Host ""
Write-Host "停止方式：执行 .\dev-scripts\stop-dev.ps1 脚本"
