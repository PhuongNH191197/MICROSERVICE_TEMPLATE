@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

echo ============================================================
echo  MICROSERVICE PLATFORM - CLEAN REBUILD AND DEPLOY
echo ============================================================
echo.

cd /d "%~dp0"

:: ============================================================
:: STEP 1: Stop and remove all containers + volumes
:: ============================================================
echo [1/5] Stopping and removing containers + volumes...
docker-compose down -v --remove-orphans
if %ERRORLEVEL% NEQ 0 (
    echo [WARN] docker-compose down had issues, continuing...
)
echo Done.
echo.

:: ============================================================
:: STEP 2: Remove old built images (keep base images like postgres, redis)
:: ============================================================
echo [2/5] Removing old project images...
for /f "tokens=*" %%i in ('docker images --format "{{.Repository}}:{{.Tag}}" ^| findstr /i "microservice_template"') do (
    docker rmi %%i --force 2>nul
)
docker image prune -f >nul 2>&1
echo Done.
echo.

:: ============================================================
:: STEP 3: Maven clean package (skip tests for speed)
:: ============================================================
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

:: ============================================================
:: STEP 4: Docker Compose build all images
:: ============================================================
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

:: ============================================================
:: STEP 5: Start all services
:: ============================================================
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

:: ============================================================
:: Summary
:: ============================================================
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
