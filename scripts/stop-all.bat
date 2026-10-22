@echo off
rem Stops what scripts\start-all.bat started: the three service windows and the Docker Compose containers.
rem Data is kept: containers are stopped, not removed, and no volume is deleted.
setlocal EnableExtensions
cd /d "%~dp0.."

echo Stopping frontend, backend and ml-service...
for %%T in ("FinTwin frontend" "FinTwin backend" "FinTwin ml-service") do (
  taskkill /FI "WINDOWTITLE eq %%~T*" /T /F >nul 2>&1
)
rem Anything still listening on the service ports (e.g. started by hand).
for %%P in (5173 8080 8000) do (
  for /f "tokens=5" %%I in ('netstat -ano ^| findstr /r /c:":%%P .*LISTENING"') do taskkill /PID %%I /T /F >nul 2>&1
)

echo Stopping PostgreSQL, Kafka and Kafka UI...
docker info >nul 2>&1
if errorlevel 1 (
  echo Docker is not running; nothing to stop there.
) else (
  docker compose -f infra\docker-compose.yml stop
)
echo Done. Start again with scripts\start-all.bat
