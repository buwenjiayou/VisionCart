@echo off
for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
    set "%%a=%%b"
)
java -jar backend\build\libs\backend-0.1.0.jar
