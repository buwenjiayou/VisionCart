param(
    [int]$Port = 18080,
    [switch]$Foreground
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdkPath = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
$gradleJarPath = Join-Path $root "backend\build\libs\backend-0.1.0.jar"
$mavenJarPath = Join-Path $root "backend\target\backend-0.1.0.jar"
$jarPath = if (Test-Path -LiteralPath $gradleJarPath) { $gradleJarPath } else { $mavenJarPath }
$logDir = Join-Path $root "logs"
$outLog = Join-Path $logDir "backend-$Port.out.log"
$errLog = Join-Path $logDir "backend-$Port.err.log"
$envPath = Join-Path $root ".env"

if (-not (Test-Path -LiteralPath $jdkPath)) {
    throw "JDK 17 not found at $jdkPath"
}
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Backend jar not found at $jarPath. Build it first with Maven."
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$env:JAVA_HOME = $jdkPath
$env:Path = "$jdkPath\bin;$env:Path"

if (Test-Path -LiteralPath $envPath) {
    Get-Content -LiteralPath $envPath | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            return
        }

        $parts = $line -split "=", 2
        if ($parts.Count -eq 2) {
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
        }
    }
}

$runtimeArgs = @(
    "--server.port=$Port",
    "--spring.servlet.multipart.max-file-size=50MB",
    "--spring.servlet.multipart.max-request-size=50MB",
    "--logging.level.root=INFO",
    "--logging.level.org.hibernate.SQL=INFO"
)

$arguments = @(
    "-jar", $jarPath
) + $runtimeArgs

if ($Foreground) {
    & java @arguments
    exit $LASTEXITCODE
}

$existing = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Port $Port is already in use by process $($existing.OwningProcess)."
    exit 0
}

$process = Start-Process `
    -FilePath (Join-Path $jdkPath "bin\java.exe") `
    -ArgumentList $arguments `
    -WorkingDirectory $root `
    -RedirectStandardOutput $outLog `
    -RedirectStandardError $errLog `
    -WindowStyle Hidden `
    -PassThru

Write-Host "Started VisionCart backend PID=$($process.Id) on http://localhost:$Port"
Write-Host "Logs: $outLog"
