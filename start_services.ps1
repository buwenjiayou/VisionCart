param(
    [switch]$SkipRedis
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$mysqlHome = "C:\Program Files\MySQL\MySQL Server 8.4"
$mysqlBin = Join-Path $mysqlHome "bin\mysqld.exe"
$mysqlConfig = Join-Path $root ".local\mysql\my.ini"
$mysqlTaskName = "VisionCartMySQL"

if (-not (Test-Path -LiteralPath $mysqlBin)) {
    throw "MySQL Server was not found at $mysqlBin"
}
if (-not (Test-Path -LiteralPath $mysqlConfig)) {
    throw "MySQL config was not found at $mysqlConfig"
}

New-Item -ItemType Directory -Force -Path (Join-Path $root "logs") | Out-Null

$mysqlPort = Get-NetTCPConnection -LocalPort 3306 -ErrorAction SilentlyContinue
if ($mysqlPort) {
    Write-Host "MySQL is already listening on port 3306."
} else {
    try {
        $task = Get-ScheduledTask -TaskName $mysqlTaskName -ErrorAction SilentlyContinue
        if (-not $task) {
            $action = New-ScheduledTaskAction `
                -Execute $mysqlBin `
                -Argument "--defaults-file=`"$mysqlConfig`"" `
                -WorkingDirectory $root
            $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
            Register-ScheduledTask -TaskName $mysqlTaskName -Action $action -Principal $principal | Out-Null
        }

        Start-ScheduledTask -TaskName $mysqlTaskName
        Start-Sleep -Seconds 3

        $mysqlPort = Get-NetTCPConnection -LocalPort 3306 -ErrorAction SilentlyContinue
        if ($mysqlPort) {
            Write-Host "Started VisionCart MySQL on localhost:3306 via scheduled task '$mysqlTaskName'."
        } else {
            Write-Host "Started scheduled task '$mysqlTaskName', but MySQL is not listening on port 3306 yet."
        }
    } catch {
        Write-Host "Could not register/start scheduled task '$mysqlTaskName': $($_.Exception.Message)"
        Write-Host "Start MySQL in a separate PowerShell with:"
        Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File .\run_mysql_foreground.ps1"
    }
}

if (-not $SkipRedis) {
    $redisService = Get-Service -Name Redis -ErrorAction SilentlyContinue
    if ($redisService -and $redisService.Status -ne "Running") {
        Start-Service -Name Redis
        Write-Host "Started Redis service."
    } elseif ($redisService) {
        Write-Host "Redis service is already running."
    } else {
        Write-Host "Redis service was not found. Install Redis.Redis or start another Redis-compatible server on port 6379."
    }
}
