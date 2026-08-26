@echo off
setlocal EnableExtensions

rem Finance attendance backend runner for Windows.
rem Put this file in the project root, beside src\Main.java and data\company-attendance.db.

set "APP_NAME=FinanceAttendanceBackend"
set "PORT=8080"
set "ROOT=%~dp0"
set "SRC=%ROOT%src\Main.java"
set "OUT=%ROOT%out"
set "MAIN_CLASS=%OUT%\Main.class"
set "LOG_DIR=%ROOT%logs"
set "LOG_FILE=%LOG_DIR%\backend.log"
set "RUNTIME_DIR=%ROOT%.runtime"
set "PORTABLE_JDK=%RUNTIME_DIR%\jdk-8-win7-32bit"
set "JAVA_EXE=java"
set "JAVAC_EXE=javac"

if /I "%~1"=="install" goto install_task
if /I "%~1"=="uninstall" goto uninstall_task
if /I "%~1"=="setup-java" goto setup_java
if /I "%~1"=="stop" goto stop_server
if /I "%~1"=="status" goto status_server
if /I "%~1"=="rebuild" goto rebuild_server
if /I "%~1"=="run" goto run_server
if /I "%~1"=="menu" goto menu
if "%~1"=="" goto run_server
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
echo  5. Stop backend running on port %PORT%
echo  6. Check backend status
echo  7. Rebuild backend classes
echo  8. Emergency stop all Java processes
echo  9. Exit
echo.
set /p choice=Choose option: 
if "%choice%"=="1" goto run_server
if "%choice%"=="2" goto install_task
if "%choice%"=="3" goto uninstall_task
if "%choice%"=="4" goto setup_java
if "%choice%"=="5" goto stop_server
if "%choice%"=="6" goto status_server
if "%choice%"=="7" goto rebuild_server
if "%choice%"=="8" goto stop_all_java
if "%choice%"=="9" exit /b 0
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

:stop_server
echo.
echo Stopping backend process using port %PORT%...
call :find_backend_pid
if defined BACKEND_PID (
    echo Killing process %BACKEND_PID%
    taskkill /PID %BACKEND_PID% /F
    echo Backend stop requested.
    echo.
    pause
    goto menu
)
echo No backend process found on port %PORT%.
echo.
pause
goto menu

:stop_all_java
echo.
echo Emergency stop: killing java.exe and javaw.exe.
echo Use this only on the dedicated backend PC.
taskkill /IM java.exe /F >nul 2>nul
taskkill /IM javaw.exe /F >nul 2>nul
echo Java processes stopped if any were running.
echo.
pause
goto menu

:status_server
echo.
call :find_backend_pid
if not defined BACKEND_PID (
    echo Backend is not listening on port %PORT%.
    echo Run this batch file again to start it.
    echo.
    pause
    goto menu
)
call :check_backend_health
if "%BACKEND_HEALTHY%"=="1" (
    echo Backend is running and responding on http://localhost:%PORT%/api/state
    call :print_phone_urls
) else (
    echo Something is using port %PORT%, but the attendance backend is not responding.
    echo Process ID: %BACKEND_PID%
    echo Choose option 5 to stop it, then run this batch file again.
)
echo.
pause
goto menu

:run_server
cd /d "%ROOT%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%OUT%" mkdir "%OUT%"

call :find_backend_pid
if defined BACKEND_PID (
    call :check_backend_health
    if not "%BACKEND_HEALTHY%"=="1" (
        echo.
        echo Port %PORT% is already used, but the attendance backend is not responding.
        echo Process ID: %BACKEND_PID%
        echo Choose option 5 to stop it, then run this batch file again.
        echo.
        pause
        goto menu
    )
    echo.
    echo Backend is already running on port %PORT% with process %BACKEND_PID%.
    echo Admin URL on this PC: http://localhost:%PORT%
    call :print_phone_urls
    echo.
    echo No restart needed.
    pause
    exit /b 0
)

call :ensure_java
if errorlevel 1 (
    echo.
    echo Backend setup needs attention. Opening menu...
    pause
    goto menu
)

if not exist "%MAIN_CLASS%" (
    call :compile_backend
    if errorlevel 1 (
        if exist "%MAIN_CLASS%" (
            echo.
            echo Compile failed, but existing backend classes are available. Starting existing backend.
        ) else (
            goto menu
        )
    )
)

echo.
echo Starting backend on port %PORT%.
call :open_firewall_port
echo Admin URL on this PC: http://localhost:%PORT%
call :print_phone_urls
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

:rebuild_server
cd /d "%ROOT%"
call :find_backend_pid
if defined BACKEND_PID (
    echo.
    echo Backend is running on port %PORT%. Stop it before rebuilding.
    echo Choose option 5, then option 7.
    echo.
    pause
    goto menu
)
call :ensure_java
if errorlevel 1 (
    echo.
    echo Backend setup needs attention.
    pause
    goto menu
)
call :compile_backend
if errorlevel 1 goto menu
echo.
echo Backend classes rebuilt.
pause
goto menu

:compile_backend
if not exist "%OUT%" mkdir "%OUT%"
echo.
echo Compiling backend...
"%JAVAC_EXE%" -source 8 -target 8 -d "%OUT%" "%SRC%"
if errorlevel 1 (
    echo.
    echo Compile failed. If the file is being used by another process, choose option 5 to stop backend first.
    pause
    exit /b 1
)
exit /b 0

:find_backend_pid
set "BACKEND_PID="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
    set "BACKEND_PID=%%P"
)
exit /b 0

:check_backend_health
set "BACKEND_HEALTHY="
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $wc=New-Object Net.WebClient; $wc.DownloadString('http://localhost:%PORT%/api/state') | Out-Null; exit 0 } catch { exit 1 }" >nul 2>nul
if not errorlevel 1 set "BACKEND_HEALTHY=1"
exit /b 0

:open_firewall_port
netsh advfirewall firewall add rule name="%APP_NAME% Port %PORT%" dir=in action=allow protocol=TCP localport=%PORT% >nul 2>nul
if errorlevel 1 (
    netsh firewall add portopening TCP %PORT% "%APP_NAME% Port %PORT%" >nul 2>nul
)
exit /b 0

:print_phone_urls
echo Phone URLs on same Wi-Fi:
for /f "tokens=2 delims=:" %%A in ('ipconfig ^| findstr /C:"IPv4 Address" /C:"IP Address"') do (
    for /f "tokens=* delims= " %%B in ("%%A") do echo   http://%%B:%PORT%
)
exit /b 0

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
set "JAVA_ZIP=%TEMP%\portable-java8-32bit-%RANDOM%.zip"
set "LOCAL_JAVA_ZIP=%ROOT%portable-java8-32bit.zip"
set "BUNDLED_JAVA_ZIP=%TEMP%\finance-attendance-java8-win7-32bit-%RANDOM%.zip"
set "JAVA_URL=https://cdn.azul.com/zulu/bin/zulu8.54.0.21-ca-jdk8.0.292-win_i686.zip"
where powershell >nul 2>nul
if errorlevel 1 (
    echo PowerShell is not available.
    echo Copy a portable Java 8 32-bit JDK folder to:
    echo %PORTABLE_JDK%
    exit /b 1
)
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%"
if exist "%ROOT%vendor\java\portable-java8-32bit.zip.part-aa" (
    echo Rebuilding bundled Java ZIP from repository parts...
    if exist "%BUNDLED_JAVA_ZIP%" del /f /q "%BUNDLED_JAVA_ZIP%"
    copy /b "%ROOT%vendor\java\portable-java8-32bit.zip.part-aa"+"%ROOT%vendor\java\portable-java8-32bit.zip.part-ab"+"%ROOT%vendor\java\portable-java8-32bit.zip.part-ac" "%BUNDLED_JAVA_ZIP%" >nul
    if errorlevel 1 (
        echo.
        echo Could not rebuild bundled Java ZIP.
        echo If the backend is already running, choose option 5 to stop it and try again.
        exit /b 1
    )
    set "JAVA_ZIP=%BUNDLED_JAVA_ZIP%"
    goto extract_portable_java
)
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
powershell -NoProfile -ExecutionPolicy Bypass -Command "$temp='%RUNTIME_DIR%\jdk-temp-%RANDOM%'; if (Test-Path $temp) { Remove-Item -Recurse -Force $temp }; New-Item -ItemType Directory -Force -Path $temp | Out-Null; $shell=New-Object -ComObject Shell.Application; $zip=$shell.NameSpace('%JAVA_ZIP%'); $dest=$shell.NameSpace($temp); if ($zip -eq $null -or $dest -eq $null) { exit 1 }; $dest.CopyHere($zip.Items(), 16); Start-Sleep -Seconds 10; $jdk=Get-ChildItem $temp | Where-Object { $_.PSIsContainer } | Select-Object -First 1; if ($jdk -eq $null) { exit 1 }; if (Test-Path '%PORTABLE_JDK%') { exit 2 }; Move-Item $jdk.FullName '%PORTABLE_JDK%'; Remove-Item -Recurse -Force $temp"
if errorlevel 1 (
    echo.
    echo Could not extract portable Java.
    echo If Java is locked, choose option 8, then option 4.
    echo You can also delete this folder manually after stopping Java:
    echo %PORTABLE_JDK%
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
