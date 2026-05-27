@echo off
setlocal

set DIR=%~dp0
set WRAPPER_JAR=%DIR%gradle\wrapper\gradle-wrapper.jar

if exist "%WRAPPER_JAR%" (
  java -jar "%WRAPPER_JAR%" %*
  exit /b %ERRORLEVEL%
)

where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)

echo Gradle wrapper jar is not present and system gradle was not found.
echo Install Gradle or run Android Studio/Gradle wrapper generation before building.
exit /b 1
