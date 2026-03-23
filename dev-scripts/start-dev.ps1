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

function Flush-LogIncrement {
    param(
        [string]$LogFile,
        [hashtable]$Cursor,
        [string]$Prefix
    )

    if (-not (Test-Path $LogFile)) {
        return
    }

    $stream = $null
    $reader = $null

    try {
        $stream = [System.IO.File]::Open($LogFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        if ($Cursor.Position -gt $stream.Length) {
            $Cursor.Position = 0
        }

        $stream.Seek($Cursor.Position, [System.IO.SeekOrigin]::Begin) | Out-Null
        $reader = New-Object System.IO.StreamReader($stream)
        $content = $reader.ReadToEnd()
        $Cursor.Position = $stream.Position

        if ([string]::IsNullOrEmpty($content)) {
            return
        }

        $lines = $content -split "`r?`n"
        foreach ($line in $lines) {
            if ([string]::IsNullOrWhiteSpace($line)) {
                continue
            }

            Write-Host "$Prefix $line"
        }
    } catch {
        Write-Warn "读取日志失败：$LogFile"
    } finally {
        if ($null -ne $reader) {
            $reader.Dispose()
        } elseif ($null -ne $stream) {
            $stream.Dispose()
        }
    }
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

    return [PSCustomObject]@{
        Name = $Name
        WorkingDirectory = $WorkingDirectory
        Command = $Command
        LogFile = $LogFile
        ErrorLogFile = $errorLogFile
        PidFile = $PidFile
        Port = $Port
        WrapperProcess = $process
        ServicePid = $null
        Ready = $false
    }
}

function Wait-ServicesReady {
    param(
        [object[]]$Services,
        [int]$TimeoutSeconds = 90,
        [object]$BackendService = $null
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $backendLogCursor = @{ Position = 0 }
    $backendErrCursor = @{ Position = 0 }

    do {
        if ($null -ne $BackendService) {
            Flush-LogIncrement -LogFile $BackendService.LogFile -Cursor $backendLogCursor -Prefix "[Backend]"
            Flush-LogIncrement -LogFile $BackendService.ErrorLogFile -Cursor $backendErrCursor -Prefix "[Backend][err]"
        }

        foreach ($service in $Services) {
            if ($service.Ready) {
                continue
            }

            $servicePid = Get-ListeningPid -Port $service.Port
            if ($null -ne $servicePid) {
                $service.ServicePid = [int]$servicePid
                $service.Ready = $true
                Set-Content -Path $service.PidFile -Value $service.ServicePid -NoNewline
                Write-Ok "$($service.Name) 已启动，PID=$($service.ServicePid)，Port=$($service.Port)"
                continue
            }

            if ($service.WrapperProcess.HasExited) {
                if ($null -ne $BackendService) {
                    Flush-LogIncrement -LogFile $BackendService.LogFile -Cursor $backendLogCursor -Prefix "[Backend]"
                    Flush-LogIncrement -LogFile $BackendService.ErrorLogFile -Cursor $backendErrCursor -Prefix "[Backend][err]"
                }

                throw "$($service.Name) 启动失败，端口 $($service.Port) 未监听。请检查日志：$($service.LogFile)"
            }
        }

        if (($Services | Where-Object { -not $_.Ready }).Count -eq 0) {
            if ($null -ne $BackendService) {
                Flush-LogIncrement -LogFile $BackendService.LogFile -Cursor $backendLogCursor -Prefix "[Backend]"
                Flush-LogIncrement -LogFile $BackendService.ErrorLogFile -Cursor $backendErrCursor -Prefix "[Backend][err]"
            }
            return
        }

        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    if ($null -ne $BackendService) {
        Flush-LogIncrement -LogFile $BackendService.LogFile -Cursor $backendLogCursor -Prefix "[Backend]"
        Flush-LogIncrement -LogFile $BackendService.ErrorLogFile -Cursor $backendErrCursor -Prefix "[Backend][err]"
    }

    $failedServices = @($Services | Where-Object { -not $_.Ready })
    foreach ($service in $failedServices) {
        try {
            if (-not $service.WrapperProcess.HasExited) {
                Stop-Process -Id $service.WrapperProcess.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {
            Write-Warn "$($service.Name) 启动超时，已终止包装进程。"
        }
    }

    $failedSummary = $failedServices | ForEach-Object { "$($_.Name)(Port=$($_.Port))" }
    throw "服务启动超时：$($failedSummary -join ', ')。请检查日志。"
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

Write-Step "并行启动前后端"
$backendService = Start-BackgroundCommand `
    -Name "Backend" `
    -WorkingDirectory $BackendDir `
    -Command "gradlew.bat bootRun" `
    -LogFile $BackendLog `
    -PidFile $BackendPidFile `
    -Port $BackendPort

$frontendService = Start-BackgroundCommand `
    -Name "Frontend" `
    -WorkingDirectory $FrontendDir `
    -Command "npm run dev" `
    -LogFile $FrontendLog `
    -PidFile $FrontendPidFile `
    -Port $FrontendPort

Write-Step "等待服务端口就绪，并实时输出后端日志"
Wait-ServicesReady -Services @($backendService, $frontendService) -BackendService $backendService

Write-Host ""
Write-Ok "前后端已启动"
Write-Host "Frontend: http://localhost:3000"
Write-Host "Backend:  http://localhost:$BackendPort"
Write-Host "Backend Log:  $BackendLog"
Write-Host "Frontend Log: $FrontendLog"
Write-Host ""
Write-Host "停止方式：执行 .\dev-scripts\stop-dev.ps1 脚本"
