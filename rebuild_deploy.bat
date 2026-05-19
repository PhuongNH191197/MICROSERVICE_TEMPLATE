@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

echo ============================================================
echo  MICROSERVICE PLATFORM - CLEAN REBUILD AND DEPLOY
echo ============================================================
echo.

cd /d "%~dp0"

:: ============================================================
:: Parse arguments
:: Usage: rebuild_deploy.bat [service-name] [service-name2] ...
:: No args = build all services
:: ============================================================

set "TARGET_SERVICES="
set "ARG_COUNT=0"
for %%A in (%*) do (
    set /a ARG_COUNT+=1
    set "TARGET_SERVICES=!TARGET_SERVICES! %%A"
)

if %ARG_COUNT% EQU 0 (
    echo Mode: FULL REBUILD ^(all services^)
    echo.
    goto :FULL_REBUILD
) else (
    echo Mode: PARTIAL REBUILD for:%TARGET_SERVICES%
    echo.
    goto :PARTIAL_REBUILD
)

:: ============================================================
:: FULL REBUILD
:: ============================================================
:FULL_REBUILD

echo [1/5] Stopping and removing containers + volumes...
docker-compose down -v --remove-orphans
if %ERRORLEVEL% NEQ 0 echo [WARN] docker-compose down had issues, continuing...
echo Done.
echo.

echo [2/5] Removing old project images...
for /f "tokens=*" %%i in ('docker images --format "{{.Repository}}:{{.Tag}}" ^| findstr /i "microservice_template"') do (
    docker rmi %%i --force 2>nul
)
docker image prune -f >nul 2>&1
echo Done.
echo.

echo [3/5] Building all Java modules with Maven...
call mvn clean package -DskipTests --no-transfer-progress
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Maven build FAILED. Fix errors above then re-run.
    pause
    exit /b 1
)
echo Done.
echo.

echo [4/5] Building Docker images...
docker-compose build --no-cache --parallel
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Docker build FAILED. Check Dockerfile errors above.
    pause
    exit /b 1
)
echo Done.
echo.

echo [5/5] Starting all services...
docker-compose up -d
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] docker-compose up FAILED.
    pause
    exit /b 1
)
echo Done.
echo.

goto :SUMMARY

:: ============================================================
:: PARTIAL REBUILD (one or more named services)
:: Map: service-name -> Maven module path (empty = no Java build)
:: ============================================================
:PARTIAL_REBUILD

for %%S in (%TARGET_SERVICES%) do (
    echo ----------------------------------------
    echo Processing service: %%S
    echo ----------------------------------------
    echo.

    :: -- Map service name to Maven module path --
    set "MVN_MODULE="
    if /i "%%S"=="auth-service"         set "MVN_MODULE=infra-services/auth-service"
    if /i "%%S"=="file-service"         set "MVN_MODULE=infra-services/file-service"
    if /i "%%S"=="notification-service" set "MVN_MODULE=infra-services/notification-service"
    if /i "%%S"=="api-gateway"          set "MVN_MODULE=infrastructure/api-gateway"
    if /i "%%S"=="config-server"        set "MVN_MODULE=infrastructure/config-server"
    if /i "%%S"=="user-profile-service" set "MVN_MODULE=business-services/user-profile-service"
    if /i "%%S"=="vocal-detector"       set "MVN_MODULE="

    :: Step 1: Stop the target service
    echo [1/4] Stopping service %%S...
    docker-compose stop %%S
    docker-compose rm -f %%S
    echo Done.
    echo.

    :: Step 2: Remove old image for this service
    echo [2/4] Removing old image for %%S...
    for /f "tokens=*" %%i in ('docker images --format "{{.Repository}}:{{.Tag}}" ^| findstr /i "%%S"') do (
        docker rmi %%i --force 2>nul
    )
    echo Done.
    echo.

    :: Step 3: Maven build (skip if no Maven module, e.g. Python services)
    if not "!MVN_MODULE!"=="" (
        echo [3/4] Building Maven module: !MVN_MODULE!
        call mvn clean package -DskipTests --no-transfer-progress -pl !MVN_MODULE! -am
        if !ERRORLEVEL! NEQ 0 (
            echo.
            echo [ERROR] Maven build FAILED for !MVN_MODULE!
            pause
            exit /b 1
        )
        echo Done.
    ) else (
        echo [3/4] Skipping Maven ^(no Java module for %%S^)
    )
    echo.

    :: Step 4: Docker build + start
    echo [4/4] Rebuilding and starting %%S...
    docker-compose build --no-cache %%S
    if !ERRORLEVEL! NEQ 0 (
        echo.
        echo [ERROR] Docker build FAILED for %%S
        pause
        exit /b 1
    )
    docker-compose up -d %%S
    if !ERRORLEVEL! NEQ 0 (
        echo.
        echo [ERROR] docker-compose up FAILED for %%S
        pause
        exit /b 1
    )
    echo Done.
    echo.
)

goto :SUMMARY

:: ============================================================
:: Summary
:: ============================================================
:SUMMARY
echo ============================================================
echo  DEPLOY COMPLETE
echo ============================================================
echo.
echo Services:
echo   API Gateway       : http://localhost:8080
echo   Auth Service      : http://localhost:8081
echo   Notification Svc  : http://localhost:8082
echo   File Service      : http://localhost:8083
echo   User Profile Svc  : http://localhost:8091
echo   Eureka Dashboard  : http://localhost:8761
echo   RabbitMQ Console  : http://localhost:15672
echo   MinIO Console     : http://localhost:9001
echo   Zipkin            : http://localhost:9411
echo.
echo Tip: docker-compose logs -f [service-name]
echo.
pause
