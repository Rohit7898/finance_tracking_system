@echo off
setlocal EnableExtensions

rem Finance attendance backend runner for Windows.
rem Put this file in the project root, beside src\Main.java and data\company-attendance.db.

set "APP_NAME=FinanceAttendanceBackend"
set "PORT=8080"
set "ROOT=%~dp0"
set "SRC=%ROOT%src\Main.java"
set "OUT=%ROOT%out"
set "LOG_DIR=%ROOT%logs"
set "LOG_FILE=%LOG_DIR%\backend.log"
set "RUNTIME_DIR=%ROOT%.runtime"
set "PORTABLE_JDK=%RUNTIME_DIR%\jdk-8-32bit"
set "JAVA_EXE=java"
set "JAVAC_EXE=javac"

if /I "%~1"=="install" goto install_task
if /I "%~1"=="uninstall" goto uninstall_task
if /I "%~1"=="setup-java" goto setup_java
if /I "%~1"=="run" goto run_server
goto menu

:menu
echo.
echo ============================================
echo  Finance Attendance Backend - Windows
echo ============================================
echo.
echo  1. Run backend now
echo  2. Install auto-start task
echo  3. Remove auto-start task
echo  4. Setup/check portable Java 8 32-bit
echo  5. Exit
echo.
set /p choice=Choose option: 
if "%choice%"=="1" goto run_server
if "%choice%"=="2" goto install_task
if "%choice%"=="3" goto uninstall_task
if "%choice%"=="4" goto setup_java
if "%choice%"=="5" exit /b 0
goto menu

:setup_java
call :ensure_java
if errorlevel 1 (
    pause
    exit /b 1
)
echo.
echo Portable Java setup is ready.
pause
goto menu

:install_task
echo.
echo Installing Windows auto-start task: %APP_NAME%
echo Run this option as Administrator if Windows denies permission.
schtasks /Create /TN "%APP_NAME%" /TR "\"%~f0\" run" /SC ONLOGON /RL HIGHEST /F
if errorlevel 1 (
    echo.
    echo Could not install the task. Right-click this file and choose "Run as administrator".
    pause
    exit /b 1
)
echo.
echo Auto-start installed. The backend will start when this Windows user logs in.
echo You can also start it now by choosing option 1.
pause
exit /b 0

:uninstall_task
echo.
echo Removing Windows auto-start task: %APP_NAME%
schtasks /Delete /TN "%APP_NAME%" /F
echo.
pause
exit /b 0

:run_server
cd /d "%ROOT%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%OUT%" mkdir "%OUT%"

call :ensure_java
if errorlevel 1 exit /b 1

echo.
echo Compiling backend...
"%JAVAC_EXE%" -source 8 -target 8 -d "%OUT%" "%SRC%"
if errorlevel 1 (
    echo.
    echo Compile failed. Check src\Main.java.
    pause
    exit /b 1
)

echo.
echo Starting backend on port %PORT%.
echo Admin URL on this PC: http://localhost:%PORT%
echo Phone URL: use this PC's Wi-Fi IPv4 address, for example http://192.168.x.x:%PORT%
echo.
echo To find the IPv4 address, run: ipconfig
echo To stop this runner, close this window or press Ctrl+C.
echo Logs: %LOG_FILE%
echo.

:restart_loop
echo [%date% %time%] Starting backend...>> "%LOG_FILE%"
"%JAVA_EXE%" -cp "%OUT%" Main >> "%LOG_FILE%" 2>&1
echo [%date% %time%] Backend stopped. Restarting in 5 seconds...>> "%LOG_FILE%"
echo Backend stopped. Restarting in 5 seconds...
timeout /t 5 /nobreak >nul
goto restart_loop

:ensure_java
echo.
echo Checking Java JDK...
where javac >nul 2>nul
if not errorlevel 1 (
    set "JAVA_EXE=java"
    set "JAVAC_EXE=javac"
    javac -version
    exit /b 0
)

call :refresh_java_path
where javac >nul 2>nul
if not errorlevel 1 (
    set "JAVA_EXE=java"
    set "JAVAC_EXE=javac"
    javac -version
    exit /b 0
)

if exist "%PORTABLE_JDK%\bin\javac.exe" (
    set "JAVA_HOME=%PORTABLE_JDK%"
    set "JAVA_EXE=%PORTABLE_JDK%\bin\java.exe"
    set "JAVAC_EXE=%PORTABLE_JDK%\bin\javac.exe"
    "%JAVAC_EXE%" -version
    exit /b 0
)

echo Java JDK was not found on this PC.
echo Downloading portable JDK into this project folder. No Windows install required.
call :download_portable_java
if errorlevel 1 exit /b 1

set "JAVA_HOME=%PORTABLE_JDK%"
set "JAVA_EXE=%PORTABLE_JDK%\bin\java.exe"
set "JAVAC_EXE=%PORTABLE_JDK%\bin\javac.exe"
"%JAVAC_EXE%" -version
exit /b 0

:download_portable_java
set "JAVA_ZIP=%TEMP%\portable-java8-32bit.zip"
set "LOCAL_JAVA_ZIP=%ROOT%portable-java8-32bit.zip"
set "JAVA_URL=https://api.adoptopenjdk.net/v3/binary/latest/8/ga/windows/x32/jdk/openj9/normal/adoptopenjdk"
where powershell >nul 2>nul
if errorlevel 1 (
    echo PowerShell is not available.
    echo Copy a portable Java 8 32-bit JDK folder to:
    echo %PORTABLE_JDK%
    exit /b 1
)
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%"
if exist "%LOCAL_JAVA_ZIP%" (
    echo Found local Java ZIP:
    echo %LOCAL_JAVA_ZIP%
    set "JAVA_ZIP=%LOCAL_JAVA_ZIP%"
    goto extract_portable_java
)
echo Downloading portable Java 8 32-bit ZIP...
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { [Net.ServicePointManager]::SecurityProtocol = 3072 } catch {}; (New-Object Net.WebClient).DownloadFile('%JAVA_URL%', '%JAVA_ZIP%')"
if errorlevel 1 (
    echo.
    echo Could not download portable Java.
    echo On another computer, download the Java ZIP and copy it here:
    echo %LOCAL_JAVA_ZIP%
    echo Then run this batch file again.
    echo.
    echo Manual fallback: download Java 8 32-bit JDK ZIP and extract it to:
    echo %PORTABLE_JDK%
    exit /b 1
)
:extract_portable_java
echo Extracting portable JDK...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$temp='%RUNTIME_DIR%\jdk-temp'; if (Test-Path $temp) { Remove-Item -Recurse -Force $temp }; New-Item -ItemType Directory -Force -Path $temp | Out-Null; $shell=New-Object -ComObject Shell.Application; $zip=$shell.NameSpace('%JAVA_ZIP%'); $dest=$shell.NameSpace($temp); if ($zip -eq $null -or $dest -eq $null) { exit 1 }; $dest.CopyHere($zip.Items(), 16); Start-Sleep -Seconds 10; $jdk=Get-ChildItem $temp -Directory | Select-Object -First 1; if ($jdk -eq $null) { exit 1 }; if (Test-Path '%PORTABLE_JDK%') { Remove-Item -Recurse -Force '%PORTABLE_JDK%' }; Move-Item $jdk.FullName '%PORTABLE_JDK%'; Remove-Item -Recurse -Force $temp"
if errorlevel 1 (
    echo.
    echo Could not extract portable Java.
    exit /b 1
)
if not exist "%PORTABLE_JDK%\bin\javac.exe" (
    echo.
    echo Portable Java folder was created, but javac.exe was not found.
    echo Expected: %PORTABLE_JDK%\bin\javac.exe
    exit /b 1
)
exit /b 0

:refresh_java_path
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-8*") do (
    set "JAVA_HOME=%%~fD"
    set "PATH=%%~fD\bin;%PATH%"
)
for /d %%D in ("%ProgramFiles%\Java\jdk1.8*") do (
    set "JAVA_HOME=%%~fD"
    set "PATH=%%~fD\bin;%PATH%"
)
for /d %%D in ("%ProgramFiles%\Java\jdk-8*") do (
    set "JAVA_HOME=%%~fD"
    set "PATH=%%~fD\bin;%PATH%"
)
exit /b 0
