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
echo  4. Install/check Java JDK
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
echo Java setup is ready.
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
javac -d "%OUT%" "%SRC%"
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
java -cp "%OUT%" Main >> "%LOG_FILE%" 2>&1
echo [%date% %time%] Backend stopped. Restarting in 5 seconds...>> "%LOG_FILE%"
echo Backend stopped. Restarting in 5 seconds...
timeout /t 5 /nobreak >nul
goto restart_loop

:ensure_java
echo.
echo Checking Java JDK...
where javac >nul 2>nul
if not errorlevel 1 (
    javac -version
    exit /b 0
)

call :refresh_java_path
where javac >nul 2>nul
if not errorlevel 1 (
    javac -version
    exit /b 0
)

echo Java JDK was not found.
echo Trying to install Eclipse Temurin JDK 17 using winget...
echo.
where winget >nul 2>nul
if errorlevel 1 (
    echo winget is not available on this Windows system.
    echo Install JDK 17 or newer manually, then run this file again.
    echo Download: https://adoptium.net/temurin/releases/
    exit /b 1
)

winget install --id EclipseAdoptium.Temurin.17.JDK --source winget --accept-package-agreements --accept-source-agreements
if errorlevel 1 (
    echo.
    echo Java install failed. Right-click this file and choose "Run as administrator", or install manually:
    echo https://adoptium.net/temurin/releases/
    exit /b 1
)

call :refresh_java_path
where javac >nul 2>nul
if errorlevel 1 (
    echo.
    echo Java installed, but PATH is not refreshed in this window.
    echo Close this window and run this batch file again.
    exit /b 1
)

javac -version
exit /b 0

:refresh_java_path
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do (
    set "JAVA_HOME=%%~fD"
    set "PATH=%%~fD\bin;%PATH%"
)
for /d %%D in ("%ProgramFiles%\Java\jdk-17*") do (
    set "JAVA_HOME=%%~fD"
    set "PATH=%%~fD\bin;%PATH%"
)
exit /b 0
