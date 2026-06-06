Get-Content D:/workspace/VisionCart/.env | Where-Object { $_ -notmatch '^\s*#' -and $_ -match '=' } | ForEach-Object {
    $parts = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
}
java -jar D:/workspace/VisionCart/backend/build/libs/backend-0.1.0.jar
