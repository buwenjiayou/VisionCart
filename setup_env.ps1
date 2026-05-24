$jdkPath = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"

if (-not (Test-Path -LiteralPath $jdkPath)) {
    throw "JDK 17 was not found at $jdkPath. Install Microsoft.OpenJDK.17 or update setup_env.ps1."
}

$env:JAVA_HOME = $jdkPath
$javaBin = Join-Path $jdkPath "bin"
if (($env:Path -split ';') -notcontains $javaBin) {
    $env:Path = "$javaBin;$env:Path"
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
java -version
javac -version
