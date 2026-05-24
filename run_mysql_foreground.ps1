$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$mysqlBin = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe"
$mysqlConfig = Join-Path $root ".local\mysql\my.ini"

if (-not (Test-Path -LiteralPath $mysqlBin)) {
    throw "MySQL Server was not found at $mysqlBin"
}
if (-not (Test-Path -LiteralPath $mysqlConfig)) {
    throw "MySQL config was not found at $mysqlConfig"
}

& $mysqlBin "--defaults-file=$mysqlConfig" --console
