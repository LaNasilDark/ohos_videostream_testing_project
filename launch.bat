@echo off
setlocal

chcp 65001

REM Allow external specification of device_key
set device_key=%1

REM Validate if device_key is empty or over 35 characters, else retrieve from hdc list targets
if "%device_key%" == "" (
    echo No device key specified. Checking for device via hdc...
    for /f %%i in ('hdc list targets') do (set device_key=%%i)
) else (
    if not "%device_key%"=="" if not "%device_key:~35%"=="" (
        echo Error: Device key exceeds 35 characters or is empty.
        pause
        exit /b 1
    )
)

REM Check if device_key is still empty or invalid after fallback
if "%device_key%" == "" (
    echo Error: No valid device key found.
    pause
    exit /b 1
)

echo Checking for %SERVER_BINARY%...
set SERVER_BINARY=remote_desktop_demo

if not exist %SERVER_BINARY% (
    echo %SERVER_BINARY% not found.
    pause
    exit /b 1
)

echo Checking hdc command...
where hdc >nul 2>nul
if not %ERRORLEVEL% == 0 (
    echo hdc command not found.
    pause
    exit /b 1
)

REM Check if device is properly listed
if "%device_key%" == "[Empty]" (
    echo No valid hdc device found.
    pause
    exit /b 1
)

echo Killing running server and deleting the file...
hdc -t %device_key% shell "pkill -9 %SERVER_BINARY%"
hdc -t %device_key% shell "rm -f /data/tmp/%SERVER_BINARY%"

echo Sending %SERVER_BINARY% file to target device...
hdc -t %device_key% shell "mkdir -p /data/tmp"
hdc -t %device_key% file send %SERVER_BINARY% /data/tmp/%SERVER_BINARY%
hdc -t %device_key% shell "chmod +x /data/tmp/%SERVER_BINARY%"

echo Starting %SERVER_BINARY% on device...
hdc -t %device_key% shell "/data/tmp/%SERVER_BINARY% >/dev/null 2>&1 &"

echo Waiting 1s for the server to be ready...
timeout /t 1 >nul

echo Starting client for LAN communication...
java -cp classes com.example.H264StreamReceiver 192.168.5.114 8000

pause
endlocal