$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $ProjectRoot ".run"
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

function Stop-ProcessFromPidFile {
    param(
        [string]$Name,
        [string]$PidFile,
        [int]$Port
    )

    if (-not (Test-Path $PidFile)) {
        Write-Warn "$Name PID file not found,尝试按端口 $Port 停止。"
    } else {
        $pidValue = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
        if ([string]::IsNullOrWhiteSpace($pidValue)) {
            Write-Warn "$Name PID is empty, skipping."
        } else {
            $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
            if ($null -eq $process) {
                Write-Warn "$Name process (PID: $pidValue) is not running."
            } else {
                try {
                    Stop-Process -Id $pidValue -Force
                    Write-Ok "$Name process (PID: $pidValue) stopped."
                } catch {
                    Write-Warn "Failed to stop $Name process (PID: $pidValue)."
                }
            }
        }

        Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    }

    $listeningPid = Get-ListeningPid -Port $Port
    if ($null -ne $listeningPid) {
        try {
            Stop-Process -Id $listeningPid -Force
            Write-Ok "$Name port $Port listener (PID: $listeningPid) stopped."
        } catch {
            Write-Warn "Failed to stop $Name port $Port listener (PID: $listeningPid)."
        }
    }
}

Write-Step "停止开发环境"

Load-EnvFile
$BackendPort = [int](Get-EnvValue -Key "SERVER_PORT" -Default "8000")
$FrontendPort = [int](Get-EnvValue -Key "FRONTEND_PORT" -Default "3000")

Stop-ProcessFromPidFile -Name "Frontend" -PidFile $FrontendPidFile -Port $FrontendPort
Stop-ProcessFromPidFile -Name "Backend" -PidFile $BackendPidFile -Port $BackendPort

Write-Host ""
Write-Ok "所有相关进程已停止。"
Write-Host ""
