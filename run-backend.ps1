Get-Content .env | Where-Object { $_ -notmatch '^\s*#' -and $_ -match '=' } | ForEach-Object {
    $parts = $_ -split '=', 2
    $name = $parts[0].Trim()
    $value = $parts[1].Trim()
    if ($name -and $value) {
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}
java -jar backend\build\libs\backend-0.1.0.jar
