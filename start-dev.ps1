param(
    [switch]$InstallDeps
)

$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "backend"
$FrontendDir = Join-Path $ProjectRoot "frontend"
$RunDir = Join-Path $ProjectRoot ".run"
$BackendLog = Join-Path $RunDir "backend.log"
$FrontendLog = Join-Path $RunDir "frontend.log"
$BackendPidFile = Join-Path $RunDir "backend.pid"
$FrontendPidFile = Join-Path $RunDir "frontend.pid"

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
        [string]$PidFile
    )

    if (Test-RunningPid $PidFile) {
        $runningPid = (Get-Content $PidFile | Select-Object -First 1).Trim()
        throw "$Name 已在运行，PID=$runningPid。请先停止现有进程。"
    }

    $process = Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c", $Command `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $LogFile `
        -RedirectStandardError $LogFile `
        -WindowStyle Hidden `
        -PassThru

    Set-Content -Path $PidFile -Value $process.Id -NoNewline
    Write-Ok "$Name 已启动，PID=$($process.Id)"
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

if ($InstallDeps -or -not (Test-Path (Join-Path $FrontendDir "node_modules"))) {
    Write-Step "安装前端依赖"
    & npm install --prefix $FrontendDir
    if ($LASTEXITCODE -ne 0) {
        throw "前端依赖安装失败"
    }
}

if (-not (Test-Path (Join-Path $ProjectRoot ".env"))) {
    Write-Warn "根目录 .env 不存在。建议先执行 .\init-dev-env.ps1"
}

if (-not (Test-Path (Join-Path $FrontendDir ".env"))) {
    Write-Warn "frontend/.env 不存在。建议先执行 .\init-dev-env.ps1"
}

Write-Step "启动后端"
Start-BackgroundCommand `
    -Name "Backend" `
    -WorkingDirectory $BackendDir `
    -Command "gradlew.bat bootRun" `
    -LogFile $BackendLog `
    -PidFile $BackendPidFile

Start-Sleep -Seconds 2

Write-Step "启动前端"
Start-BackgroundCommand `
    -Name "Frontend" `
    -WorkingDirectory $FrontendDir `
    -Command "npm run dev" `
    -LogFile $FrontendLog `
    -PidFile $FrontendPidFile

Write-Host ""
Write-Ok "前后端已启动"
Write-Host "Frontend: http://localhost:3000"
Write-Host "Backend:  http://localhost:8000"
Write-Host "Backend Log:  $BackendLog"
Write-Host "Frontend Log: $FrontendLog"
Write-Host ""
Write-Host "停止方式：结束 PID 文件中的进程，或后续补 stop 脚本。"
